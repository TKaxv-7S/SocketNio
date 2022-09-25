package com.tk.socket.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.socket.SocketException;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class SocketClientCache<S extends SocketServerSecretDto> {

    //客户端appKey属性key
    private final AttributeKey<String> appKeyAttr = AttributeKey.valueOf("appKey");

    //appKey-Secret缓存
    private final Map<String, S> secretCacheMap = new ConcurrentHashMap<>();

    //客户端缓存，key：appKey，value：客户端Channel队列
    private final Cache<String, SocketServerChannelQueue<S>> clientCache = Caffeine.newBuilder()
            //.expireAfterAccess(5, TimeUnit.MINUTES)
            .expireAfter(new Expiry<String, SocketServerChannelQueue<S>>() {
                public long expireAfterCreate(@NonNull String key, @NonNull SocketServerChannelQueue<S> value, long currentTime) {
                    return 4611686018427387903L;
                }

                public long expireAfterUpdate(@NonNull String key, @NonNull SocketServerChannelQueue<S> value, long currentTime, long currentDuration) {
                    return 4611686018427387903L;
                }

                public long expireAfterRead(@NonNull String key, @NonNull SocketServerChannelQueue<S> value, long currentTime, long currentDuration) {
                    return TimeUnit.SECONDS.toNanos(value.getTimeoutSeconds());
                }
            })
            .removalListener((String key, SocketServerChannelQueue<S> value, RemovalCause removalCause) -> {
                if (value != null) {
                    try {
                        value.clear();
                    } catch (Exception e) {
                        log.error("key：{}，移除客户端异常", key, e);
                    }
                    log.info("key：{}，客户端已离线", key);
                }
            })
            .build();

    public Boolean addClientChannel(String appKey, Channel channel, SocketNioServerWrite socketNioServerWrite) {
        SocketServerChannelQueue<S> serverChannelQueue;
        synchronized (clientCache) {
            serverChannelQueue = clientCache.getIfPresent(appKey);
            if (serverChannelQueue == null) {
                S socketSecret = getSecret(appKey);
                if (socketSecret == null) {
                    log.error("appKey：{}，客户端appKey未配置", appKey);
                    throw new SocketException("客户端appKey未配置");
                }
                serverChannelQueue = new SocketServerChannelQueue<>(socketSecret.getMaxConnection(), socketSecret.getHeartbeatTimeout());
                clientCache.put(appKey, serverChannelQueue);
            }
        }
        setAppKey(channel, appKey);
        Boolean add = serverChannelQueue.add(SocketServerChannel.build(channel, socketNioServerWrite));
        if (add) {
            log.info("channelId：{}，已加入appKey：[{}]客户端连接池", channel.id(), appKey);
        }
        return add;
    }

    public SocketServerChannel getClientChannel(Channel channel) {
        String appKey = getAppKey(channel);
        if (appKey == null) {
            return null;
        }
        return getClientChannel(appKey);
    }

//    public abstract SocketServerChannel<SocketClientCache<T>> getClientChannel(String appKey);

    public SocketServerChannel getClientChannel(String appKey) {
        SocketServerChannelQueue<S> serverChannelQueue = clientCache.getIfPresent(appKey);
        if (serverChannelQueue == null) {
            return null;
        }
        return serverChannelQueue.get();
    }

    public void delClientChannel(Channel channel) {
        String appKey = getAppKey(channel);
        if (appKey != null) {
            SocketServerChannelQueue<S> serverChannelQueue = clientCache.getIfPresent(appKey);
            if (serverChannelQueue == null) {
                return;
            }
            synchronized (clientCache) {
                serverChannelQueue.del(channel.id());
                if (serverChannelQueue.isEmpty()) {
                    clientCache.invalidate(appKey);
                }
            }
        }
    }

    public void delClientChannels(String appKey) {
        if (appKey != null) {
            SocketServerChannelQueue<S> serverChannelQueue = clientCache.getIfPresent(appKey);
            if (serverChannelQueue == null) {
                return;
            }
            synchronized (clientCache) {
                clientCache.invalidate(appKey);
            }
        }
    }

    protected SocketServerChannelQueue<S> getClientChannelQueue(String appKey) {
        return clientCache.getIfPresent(appKey);
    }

    private void setAppKey(Channel channel, String appKey) {
        channel.attr(appKeyAttr).set(appKey);
    }

    public String getAppKey(Channel channel) {
        return channel.attr(appKeyAttr).get();
    }

    public Boolean hasAppKey(Channel channel) {
        return channel.hasAttr(appKeyAttr);
    }

    public void delAppKey(Channel channel) {
        channel.attr(appKeyAttr).set(null);
    }

    public S getSecret(Channel channel) {
        String appKey = getAppKey(channel);
        if (appKey != null) {
            return secretCacheMap.get(appKey);
        }
        return null;
    }

    public S getSecret(String appKey) {
        return secretCacheMap.get(appKey);
    }

    public void addSecret(S socketSecret) {
        synchronized (secretCacheMap) {
            String appKey = socketSecret.getAppKey();
            secretCacheMap.put(appKey, socketSecret);
        }
    }

    public S delSecret(String appKey) {
        synchronized (secretCacheMap) {
            return secretCacheMap.remove(appKey);
        }
    }

    public void refreshSecret(List<S> list) {
        Set<String> appKeySet = new HashSet<>();
        list.forEach(socketSecret -> {
            addSecret(socketSecret);
            appKeySet.add(socketSecret.getAppKey());
        });
        //失效Secret删除
        delSecretByExistAppKeySet(appKeySet);
    }

    private void delSecretByExistAppKeySet(Set<String> existAppKeys) {
        synchronized (secretCacheMap) {
            for (String appKey : secretCacheMap.keySet()) {
                if (!existAppKeys.contains(appKey)) {
                    delSecret(appKey);
                }
            }
        }
    }

}
