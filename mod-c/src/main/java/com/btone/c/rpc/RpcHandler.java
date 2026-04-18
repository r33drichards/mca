package com.btone.c.rpc;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface RpcHandler {
    JsonNode handle(JsonNode params) throws Exception;
}
