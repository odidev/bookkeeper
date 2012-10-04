/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.handlers;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;

import com.google.protobuf.ByteString;

import org.apache.bookkeeper.util.MathUtils;
import org.apache.bookkeeper.util.ReflectionUtils;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ServerNotResponsibleForTopicException;
import org.apache.hedwig.filter.PipelineFilter;
import org.apache.hedwig.filter.ServerMessageFilter;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.ResponseBody;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeResponse;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionData;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.delivery.ChannelEndPoint;
import org.apache.hedwig.server.delivery.DeliveryManager;
import org.apache.hedwig.server.netty.ServerStats;
import org.apache.hedwig.server.netty.ServerStats.OpStats;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.subscriptions.SubscriptionManager;
import org.apache.hedwig.server.subscriptions.AllToAllTopologyFilter;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class SubscribeHandler extends BaseHandler implements ChannelDisconnectListener {
    static Logger logger = LoggerFactory.getLogger(SubscribeHandler.class);

    private DeliveryManager deliveryMgr;
    private PersistenceManager persistenceMgr;
    private SubscriptionManager subMgr;
    ConcurrentHashMap<TopicSubscriber, Channel> sub2Channel;
    ConcurrentHashMap<Channel, TopicSubscriber> channel2sub;
    // op stats
    private final OpStats subStats;

    private static ChannelFutureListener CLOSE_OLD_CHANNEL_LISTENER = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (!future.isSuccess()) {
                logger.warn("Failed to close old subscription channel.");
            } else {
                logger.debug("Close old subscription channel succeed.");
            }
        }
    };

    public SubscribeHandler(TopicManager topicMgr, DeliveryManager deliveryManager, PersistenceManager persistenceMgr,
                            SubscriptionManager subMgr, ServerConfiguration cfg) {
        super(topicMgr, cfg);
        this.deliveryMgr = deliveryManager;
        this.persistenceMgr = persistenceMgr;
        this.subMgr = subMgr;
        sub2Channel = new ConcurrentHashMap<TopicSubscriber, Channel>();
        channel2sub = new ConcurrentHashMap<Channel, TopicSubscriber>();
        subStats = ServerStats.getInstance().getOpStats(OperationType.SUBSCRIBE);
    }

    public void channelDisconnected(Channel channel) {
        // Evils of synchronized programming: there is a race between a channel
        // getting disconnected, and us adding it to the maps when a subscribe
        // succeeds
        synchronized (channel) {
            TopicSubscriber topicSub = channel2sub.remove(channel);
            if (topicSub != null) {
                // remove entry only currently mapped to given value.
                sub2Channel.remove(topicSub, channel);
            }
        }
    }

    @Override
    public void handleRequestAtOwner(final PubSubRequest request, final Channel channel) {

        if (!request.hasSubscribeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing subscribe request data");
            subStats.incrementFailedOps();
            return;
        }

        final ByteString topic = request.getTopic();

        MessageSeqId seqId;
        try {
            seqId = persistenceMgr.getCurrentSeqIdForTopic(topic);
        } catch (ServerNotResponsibleForTopicException e) {
            channel.write(PubSubResponseUtils.getResponseForException(e, request.getTxnId())).addListener(
                ChannelFutureListener.CLOSE);
            logger.error("Error getting current seq id for topic " + topic.toStringUtf8()
                       + " when processing subscribe request (txnid:" + request.getTxnId() + ") :", e);
            subStats.incrementFailedOps();
            ServerStats.getInstance().incrementRequestsRedirect();
            return;
        }

        final SubscribeRequest subRequest = request.getSubscribeRequest();
        final ByteString subscriberId = subRequest.getSubscriberId();

        MessageSeqId lastSeqIdPublished = MessageSeqId.newBuilder(seqId).setLocalComponent(seqId.getLocalComponent()).build();

        final long requestTime = MathUtils.now();
        subMgr.serveSubscribeRequest(topic, subRequest, lastSeqIdPublished, new Callback<SubscriptionData>() {

            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                channel.write(PubSubResponseUtils.getResponseForException(exception, request.getTxnId())).addListener(
                    ChannelFutureListener.CLOSE);
                logger.error("Error serving subscribe request (" + request.getTxnId() + ") for (topic: "
                           + topic.toStringUtf8() + " , subscriber: " + subscriberId.toStringUtf8() + ")", exception);
                subStats.incrementFailedOps();
            }

            @Override
            public void operationFinished(Object ctx, SubscriptionData subData) {

                TopicSubscriber topicSub = new TopicSubscriber(topic, subscriberId);
                synchronized (channel) {
                    if (!channel.isConnected()) {
                        // channel got disconnected while we were processing the
                        // subscribe request,
                        // nothing much we can do in this case
                        subStats.incrementFailedOps();
                        return;
                    }
                }
                // initialize the message filter
                PipelineFilter filter = new PipelineFilter();
                try {
                    // the filter pipeline should be
                    // 1) AllToAllTopologyFilter to filter cross-region messages
                    filter.addLast(new AllToAllTopologyFilter());
                    // 2) User-Customized MessageFilter
                    if (subData.hasPreferences() &&
                        subData.getPreferences().hasMessageFilter()) {
                        String messageFilterName = subData.getPreferences().getMessageFilter();
                        filter.addLast(ReflectionUtils.newInstance(messageFilterName, ServerMessageFilter.class));
                    }
                    // initialize the filter
                    filter.initialize(cfg.getConf());
                    filter.setSubscriptionPreferences(topic, subscriberId,
                                                      subData.getPreferences());
                } catch (RuntimeException re) {
                    String errMsg = "RuntimeException caught when instantiating message filter for (topic:"
                                  + topic.toStringUtf8() + ", subscriber:" + subscriberId.toStringUtf8() + ")."
                                  + "It might be introduced by programming error in message filter.";
                    logger.error(errMsg, re);
                    PubSubException pse = new PubSubException.InvalidMessageFilterException(errMsg, re);
                    subStats.incrementFailedOps();
                    channel.write(PubSubResponseUtils.getResponseForException(pse, request.getTxnId()))
                    .addListener(ChannelFutureListener.CLOSE);
                    return;
                } catch (Throwable t) {
                    String errMsg = "Failed to instantiate message filter for (topic:" + topic.toStringUtf8()
                                  + ", subscriber:" + subscriberId.toStringUtf8() + ").";
                    logger.error(errMsg, t);
                    PubSubException pse = new PubSubException.InvalidMessageFilterException(errMsg, t);
                    subStats.incrementFailedOps();
                    channel.write(PubSubResponseUtils.getResponseForException(pse, request.getTxnId()))
                    .addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                // race with channel getting disconnected while we are adding it
                // to the 2 maps
                synchronized (channel) {
                    boolean forceAttach = false;
                    if (subRequest.hasForceAttach()) {
                        forceAttach = subRequest.getForceAttach();
                    }

                    Channel oldChannel = sub2Channel.putIfAbsent(topicSub, channel);
                    if (null != oldChannel) {
                        boolean subSuccess = false;
                        if (forceAttach) {
                            // it is safe to close old channel here since new channel will be put
                            // in sub2Channel / channel2Sub so there is no race between channel
                            // getting disconnected and it.
                            ChannelFuture future = oldChannel.close();
                            future.addListener(CLOSE_OLD_CHANNEL_LISTENER);
                            logger.info("New subscribe request (" + request.getTxnId() + ") for (topic: " + topic.toStringUtf8()
                                      + ", subscriber: " + subscriberId.toStringUtf8() + ") from channel " + channel.getRemoteAddress()
                                      + " kills old channel " + oldChannel.getRemoteAddress());
                            // try replace the oldChannel
                            // if replace failure, it migth caused because channelDisconnect callback
                            // has removed the old channel.
                            if (!sub2Channel.replace(topicSub, oldChannel, channel)) {
                                // try to add it now.
                                // if add failure, it means other one has obtained the channel
                                oldChannel = sub2Channel.putIfAbsent(topicSub, channel);
                                if (null == oldChannel) {
                                    subSuccess = true;
                                }
                            } else {
                                subSuccess = true;
                            }
                        }
                        if (!subSuccess) {
                            PubSubException pse = new PubSubException.TopicBusyException(
                                "Subscriber " + subscriberId.toStringUtf8() + " for topic " + topic.toStringUtf8()
                                + " is already being served on a different channel " + oldChannel.getRemoteAddress());
                            subStats.incrementFailedOps();
                            logger.error("Error serving subscribe request (" + request.getTxnId() + ") for (topic: " + topic.toStringUtf8()
                                       + ", subscriber: " + subscriberId.toStringUtf8() + ") from channel " + channel.getRemoteAddress()
                                       + " since it already being served on a different channel " + oldChannel.getRemoteAddress());
                            channel.write(PubSubResponseUtils.getResponseForException(pse, request.getTxnId()))
                            .addListener(ChannelFutureListener.CLOSE);
                            return;
                        }
                    }
                    // channel2sub is just a cache, so we can add to it
                    // without synchronization
                    channel2sub.put(channel, topicSub);
                }
                // First write success and then tell the delivery manager,
                // otherwise the first message might go out before the response
                // to the subscribe
                SubscribeResponse.Builder subRespBuilder = SubscribeResponse.newBuilder()
                    .setPreferences(subData.getPreferences());
                ResponseBody respBody = ResponseBody.newBuilder()
                    .setSubscribeResponse(subRespBuilder).build();
                channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId(), respBody));
                logger.info("Subscribe request (" + request.getTxnId() + ") for (topic:" + topic.toStringUtf8()
                          + ", subscriber:" + subscriberId.toStringUtf8() + ") from channel " + channel.getRemoteAddress()
                          + " succeed - its subscription data is " + SubscriptionStateUtils.toString(subData));
                subStats.updateLatency(MathUtils.now() - requestTime);

                // want to start 1 ahead of the consume ptr
                MessageSeqId lastConsumedSeqId = subData.getState().getMsgId();
                MessageSeqId seqIdToStartFrom = MessageSeqId.newBuilder(lastConsumedSeqId).setLocalComponent(
                                                    lastConsumedSeqId.getLocalComponent() + 1).build();
                deliveryMgr.startServingSubscription(topic, subscriberId,
                                                     subData.getPreferences(),
                                                     seqIdToStartFrom,
                                                     new ChannelEndPoint(channel), filter);
            }
        }, null);

    }

}
