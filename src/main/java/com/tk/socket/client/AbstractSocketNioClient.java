package com.tk.socket.client;

import com.tk.socket.SocketException;
import com.tk.socket.SocketMsgHandler;
import com.tk.socket.SocketNioChannelPool;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public abstract class AbstractSocketNioClient {

    protected Bootstrap bootstrap = null;

    private final Object lockObj = new Object();

    protected SocketNioChannelPool channelPool;

    protected SocketMsgHandler msgHandler;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    private final Runnable initRunnable;

    public final AbstractSocketClientConfig config;

    protected AbstractSocketClientConfig getConfig() {
        return config;
    }

    protected Consumer<AbstractSocketNioClient> connCallback;

    public AbstractSocketNioClient(AbstractSocketClientConfig config) {
        this.config = config;
        /*this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto value, RemovalCause removalCause) -> {
                    if (value != null) {
                        CompositeByteBuf msg = value.getFull();
                        while (msg.refCnt() > 0) {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                })
                .build();*/
        this.initRunnable = () -> {
            try {
                synchronized (lockObj) {
                    if (!getIsInit()) {
                        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(Math.max(config.getBossLoopThreadCount(), 3));
                        bootstrap = new Bootstrap()
                                .group(nioEventLoopGroup)
                                .channel(NioSocketChannel.class)
                                .handler(new ChannelInitializer<SocketChannel>() {
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
                        String host = config.getHost();
                        Integer port = config.getPort();
                        if (msgHandler == null) {
                            msgHandler = new SocketMsgHandler(this::read, config.getMaxHandlerDataThreadCount());
                        }
                        channelPool = new SocketNioChannelPool(bootstrap, host, port, config.getPoolConfig());
                        log.info("TCP客户端已连接，地址：{}，端口: {}", host, port);
                        if (connCallback != null) {
                            connCallback.accept(this);
                        }
                        lockObj.notify();
                    }
                }
                if (channelPool == null) {
                    throw new SocketException("TCP客户端创建连接失败");
                }
            } catch (Exception e) {
                log.error("TCP客户端创建连接异常", e);
                throw new SocketException("TCP客户端创建连接异常");
            }
        };
    }

    public Boolean getIsInit() {
        return bootstrap != null;
    }

    public abstract Collection<ChannelHandler> setHandlers();

    public abstract SocketClientWrapMsgDto encode(ByteBuf data);

    public abstract ByteBuf decode(ByteBuf data, byte secretByte);

    public abstract void read(Channel channel, Object msg);

    public void write(Object msg) {
        Channel channel = channelPool.borrowChannel();
        try {
            channel.writeAndFlush(msg);
        } catch (Exception e) {
            log.error("写入异常", e);
            throw e;
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) channel.remoteAddress();
        if (inetSocketAddress != null) {
            log.info("服务端channelId：{}，address：{}，port：{}，已注册", channel.id(), inetSocketAddress.getAddress(), inetSocketAddress.getPort());
        } else {
            log.info("服务端channelId：{}，address：null，port：null，已注册", channel.id());
        }
    }

    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        log.info("服务端channelId：{}，已注销", ctx.channel().id());
    }

    public synchronized void initNioClientAsync() {
        if (!getIsInit()) {
            singleThreadExecutor.execute(initRunnable);
        }
    }

    public synchronized void initNioClientSync() {
        initNioClientSync(10);
    }

    public synchronized void initNioClientSync(int seconds) {
        if (!getIsInit()) {
            synchronized (lockObj) {
                singleThreadExecutor.execute(initRunnable);
                try {
                    lockObj.wait(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    log.error("TCP客户端同步创建连接异常", e);
                    throw new SocketException("TCP客户端同步创建连接异常");
                }
            }
            if (!getIsInit()) {
                throw new SocketException("TCP客户端同步创建连接失败");
            }
        }
    }

    public void shutdownNow() {
        if (getIsInit()) {
            synchronized (lockObj) {
                if (getIsInit()) {
                    if (msgHandler.shutdownNow()) {
                        channelPool.close();
                        if (bootstrap != null) {
                            //关闭线程组
                            bootstrap.config().group().shutdownGracefully();
                        }
                        bootstrap = null;
                        msgHandler = null;
                        log.info("TCP客户端已关闭");
                    }
                }
            }
        }
    }
}
