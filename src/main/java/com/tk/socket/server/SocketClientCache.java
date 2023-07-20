package com.tk.socket.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tk.socket.SocketException;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class SocketClientCache<S extends SocketServerSecretDto> {

    //appKey-Secret缓存
    private final Map<String, S> secretCacheMap = new ConcurrentHashMap<>();

    //客户端缓存，key：clientKey，value：客户端Channel队列
    private final Cache<String, SocketServerChannelQueue> cache = Caffeine.newBuilder()
            //.expireAfterAccess(5, TimeUnit.MINUTES)
            .expireAfter(new Expiry<String, SocketServerChannelQueue>() {
                public long expireAfterCreate(@NonNull String key, @NonNull SocketServerChannelQueue value, long currentTime) {
                    return 4611686018427387903L;
                }

                public long expireAfterUpdate(@NonNull String key, @NonNull SocketServerChannelQueue value, long currentTime, long currentDuration) {
                    return 4611686018427387903L;
                }

                public long expireAfterRead(@NonNull String key, @NonNull SocketServerChannelQueue value, long currentTime, long currentDuration) {
                    return TimeUnit.SECONDS.toNanos(value.getTimeoutSeconds());
                }
            })
            .removalListener((String key, SocketServerChannelQueue value, RemovalCause removalCause) -> {
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

    public Boolean addChannel(String clientKey, Channel channel, SocketNioServerWrite socketNioServerWrite) {
        SocketServerChannelQueue serverChannelQueue;
        synchronized (cache) {
            serverChannelQueue = cache.getIfPresent(clientKey);
            if (serverChannelQueue == null) {
                S socketSecret = getSecret(SocketServerChannel.getAppKey(clientKey));
                if (socketSecret == null) {
                    log.error("clientKey：{}，客户端appKey未配置", clientKey);
                    throw new SocketException("客户端appKey未配置");
                }
                serverChannelQueue = new SocketServerChannelQueue(socketSecret.getMaxConnection(), socketSecret.getHeartbeatTimeout());
                cache.put(clientKey, serverChannelQueue);
            }
        }
        Boolean add = serverChannelQueue.add(SocketServerChannel.build(clientKey, channel, socketNioServerWrite));
        if (add) {
            log.info("channelId：{}，已加入clientKey：[{}]客户端连接池", channel.id(), clientKey);
        }
        return add;
    }

    public SocketServerChannel getChannel(Channel channel) {
        String clientKey = getClientKey(channel);
        if (clientKey == null) {
            return null;
        }
        return getChannel(clientKey);
    }

//    public abstract SocketServerChannel<SocketClientCache<T>> getClientChannel(String appKey);

    public SocketServerChannel getChannel(String clientKey) {
        SocketServerChannelQueue serverChannelQueue = cache.getIfPresent(clientKey);
        if (serverChannelQueue == null) {
            return null;
        }
        return serverChannelQueue.get();
    }

    public SocketServerChannel getChannel(String appKey, String appId) {
        String clientKey;
        if (StringUtils.isNotBlank(appId)) {
            clientKey = appKey + "[" + appId + "]";
        } else {
            clientKey = appKey;
        }
        SocketServerChannelQueue serverChannelQueue = cache.getIfPresent(clientKey);
        if (serverChannelQueue == null) {
            return null;
        }
        return serverChannelQueue.get();
    }

    public void delChannel(Channel channel) {
        String clientKey = getClientKey(channel);
        if (clientKey != null) {
            SocketServerChannelQueue serverChannelQueue = cache.getIfPresent(clientKey);
            if (serverChannelQueue == null) {
                return;
            }
            synchronized (cache) {
                serverChannelQueue.del(channel.id());
                if (serverChannelQueue.isEmpty()) {
                    cache.invalidate(clientKey);
                }
            }
        }
    }

    public void delChannels(String clientKey) {
        if (clientKey != null) {
            SocketServerChannelQueue serverChannelQueue = cache.getIfPresent(clientKey);
            if (serverChannelQueue == null) {
                return;
            }
            synchronized (cache) {
                cache.invalidate(clientKey);
            }
        }
    }

    protected SocketServerChannelQueue getChannelQueue(String clientKey) {
        return cache.getIfPresent(clientKey);
    }

    public String getAppKey(Channel channel) {
        return SocketServerChannel.getAppKey(channel);
    }

    public Boolean hasAppKey(Channel channel) {
        return SocketServerChannel.hasAppKey(channel);
    }

    public Boolean hasAppKey(SocketServerChannel serverChannel) {
        return serverChannel.hasAppKey();
    }

    public String getClientKey(Channel channel) {
        return SocketServerChannel.getClientKey(channel);
    }

    public Boolean hasClientKey(Channel channel) {
        return SocketServerChannel.hasClientKey(channel);
    }

    public Boolean hasClientKey(SocketServerChannel serverChannel) {
        return serverChannel.hasClientKey();
    }

    public S getSecret(Channel channel) {
        return secretCacheMap.get(SocketServerChannel.getAppKey(channel));
    }

    public S getSecret(String appKey) {
        return secretCacheMap.get(appKey);
    }

    public S getSecret(SocketServerChannel serverChannel) {
        return secretCacheMap.get(serverChannel.getAppKey());
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

    public Set<String> secretSet() {
        synchronized (secretCacheMap) {
            return secretCacheMap.keySet();
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
