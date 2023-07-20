package com.tk.socket;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SocketNioChannelPool {

    private final GenericObjectPool<Channel> pool;

    public SocketNioChannelPool() {
        throw new SocketException("该类不可使用无参构造函数实例化");
    }

    public SocketNioChannelPool(Bootstrap bootstrap, String host, Integer port, GenericObjectPoolConfig<Channel> config) {
        this.pool = new GenericObjectPool<>(new ChannelFactory(bootstrap, host, port), config);
        //initChannel(config.getMinIdle());
    }

    /*private void initChannel(int count) {
        List<Channel> list = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            list.add(borrowChannel());
        }

        for (Channel channel : list) {
            returnChannel(channel);
        }
    }*/

    public Channel borrowChannel() {
        try {
            return pool.borrowObject();
        } catch (Exception e) {
            throw new SocketException(e, "获取连接失败");
        }
    }

    public Channel borrowChannel(final long borrowMaxWaitMillis) {
        try {
            return pool.borrowObject(borrowMaxWaitMillis);
        } catch (Exception e) {
            throw new SocketException(e, "获取连接失败");
        }
    }

    public void returnChannel(Channel channel) {
        pool.returnObject(channel);
    }

    public void close() {
        pool.close();
    }

    public boolean isClosed() {
        return pool.isClosed();
    }

    static class ChannelFactory extends BasePooledObjectFactory<Channel> {

        private final Bootstrap bootstrap;

        private final String host;

        private final Integer port;

        public ChannelFactory() {
            throw new SocketException("该类不可使用无参构造函数实例化");
        }

        public ChannelFactory(Bootstrap bootstrap, String host, Integer port) {
            this.bootstrap = bootstrap;
            this.host = host;
            this.port = port;
        }

        @Override
        public Channel create() throws Exception {
            ChannelFuture connect = bootstrap.connect(new InetSocketAddress(host, port));
            if (connect.await(5, TimeUnit.SECONDS)) {
                log.info("创建socket连接成功");
                return connect.channel();
            }
            log.error("创建socket连接失败");
            return null;
        }

        @Override
        public PooledObject<Channel> wrap(Channel channel) {
            return new DefaultPooledObject<>(channel);
        }

        @Override
        public boolean validateObject(PooledObject<Channel> pooledObject) {
            try {
                Channel object = pooledObject.getObject();
                if (object != null) {
                    return object.isActive();
                }
            } catch (Exception e) {
                log.error("验证socket连接异常", e);
            }
            return false;
        }

        @Override
        public void destroyObject(PooledObject<Channel> pooledObject) throws Exception {
            Channel channel = pooledObject.getObject();
            channel.close();
            super.destroyObject(pooledObject);
        }

    }
}