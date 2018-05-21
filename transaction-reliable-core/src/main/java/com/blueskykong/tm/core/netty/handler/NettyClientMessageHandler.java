package com.blueskykong.tm.core.netty.handler;

import com.blueskykong.tm.common.config.TxConfig;
import com.blueskykong.tm.common.enums.NettyMessageActionEnum;
import com.blueskykong.tm.common.enums.NettyResultEnum;
import com.blueskykong.tm.common.helper.SpringBeanUtils;
import com.blueskykong.tm.common.holder.IdWorkerUtils;
import com.blueskykong.tm.common.holder.LogUtil;
import com.blueskykong.tm.common.netty.bean.HeartBeat;
import com.blueskykong.tm.common.netty.bean.TxTransactionGroup;
import com.blueskykong.tm.common.netty.bean.TxTransactionItem;
import com.blueskykong.tm.core.concurrent.task.BlockTask;
import com.blueskykong.tm.core.concurrent.task.BlockTaskHelper;
import com.blueskykong.tm.core.netty.NettyClientService;
import com.blueskykong.tm.core.service.ModelNameService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author keets
 */
@ChannelHandler.Sharable
public class NettyClientMessageHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyClientMessageHandler.class);

    public volatile static boolean net_state = false;

    private static volatile ChannelHandlerContext ctx;

    private static final HeartBeat HEART_BEAT = new HeartBeat();

    private TxConfig txConfig;

    public void setTxConfig(TxConfig txConfig) {
        this.txConfig = txConfig;
    }

    private ModelNameService modelNameService;

    public NettyClientMessageHandler(ModelNameService modelNameService) {
        this.modelNameService = modelNameService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, final Object msg) throws Exception {
        net_state = true;
        HeartBeat heartBeat = (HeartBeat) msg;
        String server_ctx = ctx.channel().remoteAddress().toString();
        final NettyMessageActionEnum actionEnum = NettyMessageActionEnum.acquireByCode(heartBeat.getAction());
        LogUtil.debug(LOGGER, "接收服务端 {} ，执行的动作为:{}", () -> server_ctx, actionEnum::getDesc);
        try {
            switch (actionEnum) {
                case HEART:
                    break;
                case RECEIVE:
                    receivedCommand(heartBeat.getKey(), heartBeat.getResult());
                    break;
                case ROLLBACK:
                    notify(heartBeat);
                    break;
                case COMPLETE_COMMIT:
                    notify(heartBeat);
                    break;
                case GET_TRANSACTION_GROUP_STATUS:
                    final BlockTask blockTask = BlockTaskHelper.getInstance().getTask(heartBeat.getKey());
                    final TxTransactionGroup txTransactionGroup = heartBeat.getTxTransactionGroup();
                    blockTask.setAsyncCall(objects -> txTransactionGroup.getStatus());
                    blockTask.signal();
                    break;
                case FIND_TRANSACTION_GROUP_INFO:
                    final BlockTask task = BlockTaskHelper.getInstance().getTask(heartBeat.getKey());
                    task.setAsyncCall(objects -> heartBeat.getTxTransactionGroup());
                    task.signal();
                    break;
                default:
                    break;

            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void notify(HeartBeat heartBeat) {
        final List<TxTransactionItem> txTransactionItems = heartBeat.getTxTransactionGroup()
                .getItemList();
        if (CollectionUtils.isNotEmpty(txTransactionItems)) {
            final TxTransactionItem item = txTransactionItems.get(0);
            final BlockTask task = BlockTaskHelper.getInstance().getTask(item.getTaskKey());
            task.setAsyncCall(objects -> item.getStatus());
            task.signal();
        }
    }

    private void receivedCommand(String key, int result) {
        final BlockTask blockTask = BlockTaskHelper.getInstance().getTask(key);
        if (Objects.nonNull(blockTask)) {
            blockTask.setAsyncCall(objects -> result == NettyResultEnum.SUCCESS.getCode());
            blockTask.signal();
        }
//        LogUtil.debug(LOGGER, () -> "create tx-group successful!");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LogUtil.info(LOGGER, "与服务器断开连接服务器");
        super.channelInactive(ctx);
        SpringBeanUtils.getInstance().getBean(NettyClientService.class).doConnect();

    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        NettyClientMessageHandler.ctx = ctx;
        LogUtil.info(LOGGER, "建立链接-->" + ctx);
        net_state = true;
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        //心跳配置
        if (IdleStateEvent.class.isAssignableFrom(evt.getClass())) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                SpringBeanUtils.getInstance().getBean(NettyClientService.class).doConnect();
            } else if (event.state() == IdleState.WRITER_IDLE) {
                //表示已经多久没有发送数据了
                HEART_BEAT.setAction(NettyMessageActionEnum.HEART.getCode());
                HEART_BEAT.setMetaInfo(modelNameService.findClientMetaInfo());
                HEART_BEAT.setSerialProtocol(this.txConfig.getNettySerializer());
                ctx.writeAndFlush(HEART_BEAT);
                LogUtil.debug(LOGGER, () -> "向服务端发送的心跳");
            } else if (event.state() == IdleState.ALL_IDLE) {
                //表示已经多久既没有收到也没有发送数据了
                SpringBeanUtils.getInstance().getBean(NettyClientService.class).doConnect();
            }
        }
    }


    /**
     * 向TxManager 发消息
     *
     * @param heartBeat 定义的数据传输对象
     * @return Object
     */
    public Object sendTxManagerMessage(HeartBeat heartBeat) {
        if (ctx != null && ctx.channel() != null && ctx.channel().isActive()) {
            final String sendKey = IdWorkerUtils.getInstance().createTaskKey();
            BlockTask sendTask = BlockTaskHelper.getInstance().getTask(sendKey);
            heartBeat.setKey(sendKey);
            ctx.writeAndFlush(heartBeat);
            final ScheduledFuture<?> schedule = ctx.executor()
                    .schedule(() -> {
                        if (!sendTask.isNotify()) {
                            if (NettyMessageActionEnum.GET_TRANSACTION_GROUP_STATUS.getCode()
                                    == heartBeat.getAction()) {
                                sendTask.setAsyncCall(objects -> NettyResultEnum.TIME_OUT.getCode());
                            } else if (NettyMessageActionEnum.FIND_TRANSACTION_GROUP_INFO.getCode()
                                    == heartBeat.getAction()) {
                                sendTask.setAsyncCall(objects -> null);
                            } else {
                                sendTask.setAsyncCall(objects -> false);
                            }
                            sendTask.signal();
                        }
                    }, txConfig.getDelayTime(), TimeUnit.SECONDS);
            //发送线程在此等待，等tm是否 正确返回（正确返回唤醒） 返回错误或者无返回通过上面的调度线程唤醒
            sendTask.await();

            //如果已经被唤醒，就不需要去执行调度线程了 ，关闭上面的调度线程池中的任务
            if (!schedule.isDone()) {
                schedule.cancel(false);
            }
            try {
                return sendTask.getAsyncCall().callBack();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                return null;
            } finally {
                BlockTaskHelper.getInstance().removeByKey(sendKey);
            }

        } else {
            return null;
        }

    }


    /**
     * 向TxManager 异步 发送消息
     *
     * @param heartBeat 定义的数据传输对象
     */
    public void asyncSendTxManagerMessage(HeartBeat heartBeat) {
        if (ctx != null && ctx.channel() != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(heartBeat);
        }

    }

}
