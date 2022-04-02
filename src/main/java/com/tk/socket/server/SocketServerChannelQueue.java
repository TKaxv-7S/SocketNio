package com.tk.socket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class SocketServerChannelQueue {

    private static final int DEF_MAX_SIZE = 10;

    private static final int DEF_TIMEOUT_SECONDS = 180;

    private int maxSize;

    private int timeoutSeconds;

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    private final Map<ChannelId, SocketServerChannel> map = new ConcurrentHashMap<>();

    private final Queue<ChannelId> queue = new LinkedBlockingQueue<>();

    public SocketServerChannelQueue() {
        this(DEF_MAX_SIZE, DEF_TIMEOUT_SECONDS);
    }

    public SocketServerChannelQueue(int maxSize) {
        this(maxSize, DEF_TIMEOUT_SECONDS);
    }

    public SocketServerChannelQueue(int maxSize, int timeoutSeconds) {
        this.maxSize = maxSize;
        this.timeoutSeconds = timeoutSeconds;

    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel) {
        this(socketServerChannel, DEF_MAX_SIZE, DEF_TIMEOUT_SECONDS);
    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel, int maxSize) {
        this(socketServerChannel, maxSize, DEF_TIMEOUT_SECONDS);
    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel, int maxSize, int timeoutSeconds) {
        this.maxSize = maxSize;
        this.timeoutSeconds = timeoutSeconds;
        ChannelId channelId = socketServerChannel.getChannelId();
        this.map.put(channelId, socketServerChannel);
        this.queue.add(channelId);
    }

    public Boolean add(SocketServerChannel socketServerChannel) {
        synchronized (this) {
            if (map.size() >= maxSize) {
                Iterator<SocketServerChannel> iterator = map.values().iterator();
                while (iterator.hasNext()) {
                    SocketServerChannel clientChannel = iterator.next();
                    Channel channel = clientChannel.getChannel();
                    if (!channel.isActive()) {
                        ChannelId channelId = clientChannel.getChannelId();
                        channel.close();
                        iterator.remove();
                        queue.remove(channelId);
                    }
                }
                if (map.size() >= maxSize) {
                    return false;
                }
            }
            if (socketServerChannel.getChannel().isActive()) {
                ChannelId channelId = socketServerChannel.getChannelId();
                if (!map.containsKey(channelId)) {
                    map.put(channelId, socketServerChannel);
                    queue.add(channelId);
                }
                return true;
            }
            return false;
        }
    }

    public SocketServerChannel get() {
        synchronized (this) {
            ChannelId channelId = queue.poll();
            if (channelId == null) {
                return null;
            }
            SocketServerChannel socketServerChannel = null;
            while (socketServerChannel == null) {
                socketServerChannel = map.get(channelId);
                if (socketServerChannel == null) {
                    channelId = queue.poll();
                    if (channelId == null) {
                        return null;
                    }
                    continue;
                }
                Channel channel = socketServerChannel.getChannel();
                if (!channel.isActive()) {
                    channel.close();
                    socketServerChannel = null;
                    map.remove(channelId);
                    channelId = queue.poll();
                    if (channelId == null) {
                        return null;
                    }
                }
            }
            queue.add(channelId);
            return socketServerChannel;
        }
    }

    public void del(ChannelId channelId) {
        synchronized (this) {
            SocketServerChannel removeSocketServerChannel = map.remove(channelId);
            if (removeSocketServerChannel != null) {
                removeSocketServerChannel.getChannel().close();
            }
            queue.remove(channelId);
        }
    }

    public void del(SocketServerChannel socketServerChannel) {
        synchronized (this) {
            ChannelId channelId = socketServerChannel.getChannelId();
            SocketServerChannel removeSocketServerChannel = map.remove(channelId);
            if (removeSocketServerChannel != null) {
                removeSocketServerChannel.getChannel().close();
            }
            queue.remove(channelId);
        }
    }

    public void clear() {
        synchronized (this) {
            Iterator<SocketServerChannel> iterator = map.values().iterator();
            while (iterator.hasNext()) {
                SocketServerChannel socketServerChannel = iterator.next();
                socketServerChannel.getChannel().close();
                iterator.remove();
            }
            queue.clear();
        }
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.size() <= 0;
    }

    public Set<ChannelId> keySet() {
        return map.keySet();
    }

    public Collection<SocketServerChannel> values() {
        return map.values();
    }

    public Set<Map.Entry<ChannelId, SocketServerChannel>> entrySet() {
        return map.entrySet();
    }
}
