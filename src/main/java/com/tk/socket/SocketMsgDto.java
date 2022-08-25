package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import lombok.Data;

import java.io.Serializable;

@Data
public class SocketMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer size;

    //头部，5字节
    private ByteBuf headMsg;

    private CompositeByteBuf fullMsg;

    public SocketMsgDto() {
    }

    public SocketMsgDto(Integer size, CompositeByteBuf fullMsg) {
        this.size = size;
        this.fullMsg = fullMsg;
    }
}
