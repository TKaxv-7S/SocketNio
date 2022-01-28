package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public abstract class AbstractSocketNioClient {

    private Boolean isInit = false;

    public Boolean getIsInit() {
        return isInit;
    }

    private final Object lockObj = new Object();

    private Channel channel;

    public Channel getChannel() {
        return channel;
    }

    private MsgHandler msgHandler;

    public abstract String setHost();

    public abstract Integer setPort();

    public abstract SocketEncodeDto encode(byte[] data);

    public abstract byte[] decode(byte[] data, byte secretByte);

    public abstract Consumer<byte[]> setDataConsumer();

    public abstract Integer setMsgSizeLimit();

    public abstract int setMaxHandlerDataThreadCount();

    public abstract int setSingleThreadDataConsumerCount();

    @ChannelHandler.Sharable
    class ClientInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            log.info("服务端channelId：{}，已注册", ctx.channel().id());
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            log.info("服务端channelId：{}，已注销", ctx.channel().id());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;
            msgHandler.readMsg(ctx, msg);
        }
    }

    @ChannelHandler.Sharable
    class ClientOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            msgHandler.handlerWrite(ctx, msg, promise);
        }
    }

    public boolean isClosed() {
        return channel == null || !channel.isActive();
    }

    public synchronized void close() {
        if (!isClosed()) {
            synchronized (channel) {
                msgHandler.cleanUpReadCacheMap();
                channel.close();
                isInit = false;
            }
        }
        log.info("连接已关闭");
    }

    public void write(byte[] data) {
        msgHandler.write(channel, data);
    }

    public boolean writeAck(byte[] data, int seconds) {
        if (isClosed()) {
            initNioClientAsync();
            return false;
        }
        return msgHandler.writeAck(channel, data, Math.min(seconds, 10));
    }

    public boolean writeAck(byte[] data) {
        return writeAck(data, 10);
    }

    public synchronized void initNioClientAsync() {
        if (!isInit || isClosed()) {
            ThreadUtil.execute(() -> {
                EventLoopGroup bossGroup = null;
                synchronized (lockObj) {
                    try {
                        if (!isInit || isClosed()) {
                            //主线程组
                            String host = setHost();
                            Integer port = setPort();
                            Bootstrap bootstrap = new Bootstrap()
                                    .group(bossGroup = new NioEventLoopGroup(2))
                                    .channel(NioSocketChannel.class)
                                    .handler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                                            socketChannel.pipeline()
                                                    .addLast(new ClientInHandler())
                                                    .addLast(new ClientOutHandler());
                                        }
                                    })
                                    //首选直接内存
                                    .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                                    //设置队列大小
                                    //.option(ChannelOption.SO_BACKLOG, 1024)
                                    .option(ChannelOption.TCP_NODELAY, true)
                                    .option(ChannelOption.SO_KEEPALIVE, true)
                                    .option(ChannelOption.SO_RCVBUF, 4096 * 1024)
                                    .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                                    //客户端低水位线设置为1M，高水位线设置为2M
                                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024));
                            ChannelFuture channelFuture = bootstrap.connect(host, port);
                            channel = channelFuture.sync().channel();
                            if (msgHandler == null) {
                                msgHandler = new MsgHandler(
                                        30
                                        , setMsgSizeLimit()
                                        , (channelId, data, secretByte) -> decode(data, secretByte)
                                        , (channelId, data) -> encode(data)
                                        , (channelHandlerContext, bytes) -> setDataConsumer().accept(bytes)
                                        , setMaxHandlerDataThreadCount()
                                        , setSingleThreadDataConsumerCount()
                                );
                            }
                            log.info("SocketNioClient已连接，地址：{}，端口: {}", host, port);
                            isInit = true;
                            channel.closeFuture().sync();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        //关闭主线程组
                        if (bossGroup != null) {
                            bossGroup.shutdownGracefully();
                        }
                    }
                }
            });
        }
    }
}
