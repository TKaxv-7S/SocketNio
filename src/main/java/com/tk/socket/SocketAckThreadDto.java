package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketAckThreadDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean isAck;

    public SocketAckThreadDto() {
        this.isAck = false;
    }
}
