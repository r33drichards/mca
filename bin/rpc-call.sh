#!/usr/bin/env bash
# Simple RPC wrapper that avoids nix stdbuf issues
unset DYLD_INSERT_LIBRARIES _STDBUF_O _STDBUF_E _STDBUF_I

/usr/bin/curl -s -X POST "http://127.0.0.1:25591/rpc" \
  -H "Authorization: Bearer _xXBEi51WVDjoPA8HcHdA3eXHRWaXXJR-y04LV37ZwY" \
  -H "Content-Type: application/json" \
  -d "$1"
