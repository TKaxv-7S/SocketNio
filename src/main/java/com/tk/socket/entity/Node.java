package com.tk.socket.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class Node<N> implements Serializable {

    private static final long serialVersionUID = 1L;

    private N node;

    public Node(N node) {
        this.node = node;
    }

    public Node() {

    }

    public static <N> Node<N> newNode(N node) {
        return new Node<>(node);
    }

    public static <N> Node<N> newNode() {
        return new Node<>();
    }

}