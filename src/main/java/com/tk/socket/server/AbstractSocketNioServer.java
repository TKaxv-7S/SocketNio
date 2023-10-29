package com.tk.socket.server;

import com.tk.socket.SocketException;
import com.tk.socket.SocketMsgHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public abstract class AbstractSocketNioServer {

    private ServerBootstrap bootstrap;

    private Channel channel = null;

    private SocketMsgHandler socketMsgHandler;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    private final Runnable initRunnable;

    public final SocketServerConfig config;

    public AbstractSocketNioServer(SocketServerConfig config) {
        this.config = config;
        this.initRunnable = () -> {
            try {
                synchronized (this) {
                    if (!getIsInit()) {
                        bootstrap = new ServerBootstrap()
                                .group(new NioEventLoopGroup((Math.max(config.getBossLoopThreadCount(), 2))), new NioEventLoopGroup(config.getEventLoopThreadCount()))
                                .channel(NioServerSocketChannel.class)
                                .childHandler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                                        Collection<ChannelHandler> handlers = setHandlers();
                                        if (handlers != null) {
                                            ChannelPipeline pipeline = socketChannel.pipeline();
                                            for (ChannelHandler channelHandler : handlers) {
                                                pipeline.addLast(channelHandler);
                                            }
                                        }
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
                        Integer port = config.getPort();
                        ChannelFuture future = bootstrap.bind(port).sync();
                        channel = future.channel();
                        if (socketMsgHandler == null) {
                            socketMsgHandler = new SocketMsgHandler(
                                    setDataConsumer()
                                    , config.getMaxHandlerDataThreadCount()
                            );
                        }
                        log.info("SocketNioServer已启动，开始监听端口: {}", port);
                        this.notify();
                    }
                }
                if (channel == null) {
                    throw new SocketException("TCP服务端初始化连接失败");
                }
            } catch (Exception e) {
                log.error("TCP服务端初始化连接异常", e);
                throw new SocketException("TCP服务端初始化连接异常");
            }
        };
    }

    public Boolean getIsInit() {
        return channel != null;
    }

    public abstract Collection<ChannelHandler> setHandlers();

    public abstract ByteBuf encode(Channel channel, ByteBuf data);

    public abstract ByteBuf decode(Channel channel, ByteBuf data, byte secretByte);

    public abstract BiConsumer<Channel, byte[]> setDataConsumer();

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        if (inetSocketAddress != null) {
            log.info("客户端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
        } else {
            log.info("客户端channelId：{}，address：null，port：null，已注册", channel.id());
        }
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        log.info("客户端channelId：{}，已注销", ctx.channel().id());
    }

    protected void read(Channel channel, ByteBuf data) {
        socketMsgHandler.read(channel, data);
    }

    public void write(Channel socketChannel, ByteBuf data) {
        socketMsgHandler.write(socketChannel, data);
    }

    public void initNioServerAsync() {
        if (!getIsInit()) {
            singleThreadExecutor.execute(initRunnable);
        }
    }

    public void initNioServerSync() {
        initNioServerSync(10);
    }

    public void initNioServerSync(int seconds) {
        if (!getIsInit()) {
            synchronized (this) {
                singleThreadExecutor.execute(initRunnable);
                try {
                    this.wait(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    log.error("TCP服务端同步初始化连接异常", e);
                    throw new SocketException("TCP服务端同步初始化连接异常");
                }
            }
            if (!getIsInit()) {
                throw new SocketException("TCP服务端同步初始化连接失败");
            }
        }
    }

    public void shutdownNow() {
        if (getIsInit()) {
            synchronized (this) {
                if (getIsInit()) {
                    if (socketMsgHandler.shutdownNow()) {
                        channel.close();
                        if (bootstrap != null) {
                            ServerBootstrapConfig config = bootstrap.config();
                            //关闭主线程组
                            config.group().shutdownGracefully();
                            //关闭工作线程组
                            config.childGroup().shutdownGracefully();
                        }
                        bootstrap = null;
                        channel = null;
                        socketMsgHandler = null;
                    }
                }
            }
        }
        log.info("SocketNioServer已关闭");
    }

}
