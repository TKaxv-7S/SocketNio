package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
public abstract class AbstractSocketNioServer {

    private Boolean isInit = false;

    public Boolean getIsInit() {
        return isInit;
    }

    private Integer port;

    private MsgHandler msgHandler;

    public abstract Integer setPort();

    public abstract SocketEncodeDto encode(ChannelId channelId, byte[] data);

    public abstract byte[] decode(ChannelId channelId, byte[] data, byte secretByte);

    public abstract BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer();

    public abstract Integer setMsgSizeLimit();

    public abstract int setEventLoopThreadCount();

    public abstract int setMaxHandlerDataThreadCount();

    public abstract int setSingleThreadDataConsumerCount();

    @ChannelHandler.Sharable
    class ServerInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            log.info("客户端channelId：{}，已注册", ctx.channel().id());
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            log.info("客户端channelId：{}，已注销", ctx.channel().id());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;
            msgHandler.readMsg(ctx, msg);
        }
    }

    @ChannelHandler.Sharable
    class ServerOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            msgHandler.handlerWrite(ctx, msg, promise);
        }
    }

    public void write(Channel socketChannel, byte[] data) {
        msgHandler.write(socketChannel, data);
    }

    /**
     * 服务端等待确认时间最久10秒
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, byte[] data, int seconds) {
        return msgHandler.writeAck(socketChannel, data, Math.min(seconds, 10));
    }

    public boolean writeAck(Channel socketChannel, byte[] data) {
        return writeAck(socketChannel, data, 10);
    }

    public synchronized void initNioServer() {
        ThreadUtil.execute(() -> {
            //new 一个主线程组
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            //new 一个工作线程组
            EventLoopGroup workGroup = new NioEventLoopGroup(setEventLoopThreadCount());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline()
                                    .addLast(new ServerInHandler())
                                    .addLast(new ServerOutHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    //首选直接内存
                    .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                    //设置队列大小
//                .option(ChannelOption.SO_BACKLOG, 1024)
                    // 两小时内没有数据的通信时,TCP会自动发送一个活动探测数据报文
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_RCVBUF, 128 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                    //服务端低水位线设置为3M，高水位线设置为6M
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(3 * 1024 * 1024, 6 * 1024 * 1024));
            try {
                port = setPort();
                if (msgHandler == null) {
                    msgHandler = new MsgHandler(
                            10
                            , setMsgSizeLimit()
                            , this::decode
                            , this::encode
                            , setDataConsumer()
                            , setMaxHandlerDataThreadCount()
                            , setSingleThreadDataConsumerCount()
                    );
                }
                ChannelFuture future = bootstrap.bind(port).sync();
                log.info("SocketNioServer已启动，开始监听端口: {}", port);
                isInit = true;
                future.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //关闭主线程组
                bossGroup.shutdownGracefully();
                //关闭工作线程组
                workGroup.shutdownGracefully();
            }
        });
    }

}
