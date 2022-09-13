package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketWrapMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private byte[] data;

    private byte secretByte;

    public SocketWrapMsgDto() {
    }

    public SocketWrapMsgDto(byte[] data, byte secretByte) {
        this.data = data;
        this.secretByte = secretByte;
    }

}
