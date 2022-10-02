package com.github.lhg.core.http;

public class JsonResponse {
    public final String json;
    public final int statusCode;

    public JsonResponse(String json, int statusCode) {
        this.json = json;
        this.statusCode = statusCode;
    }
}
