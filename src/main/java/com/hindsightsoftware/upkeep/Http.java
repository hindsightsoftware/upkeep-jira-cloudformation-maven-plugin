package com.hindsightsoftware.upkeep;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.util.List;

public class Http {
    public enum Method {
        POST,
        GET
    }

    public static class Response {
        private final HttpResponse response;
        private final HttpEntity entity;

        public Response(HttpResponse response){
            this.response = response;
            this.entity = response.getEntity();
        }

        public String getBody() throws IOException {
            InputStream inputStream = entity.getContent();

            final StringBuilder out = new StringBuilder();
            Reader in = new InputStreamReader(inputStream, "UTF-8");
            char buffer[] = new char[1024];
            try {
                while(inputStream.available() > 0){
                    int len = in.read(buffer, 0, 1024);
                    if(len <= 0)break;
                    out.append(buffer, 0, len);
                }
                return out.toString();
            } finally {
                inputStream.close();
            }
        }

        public int getStatusCode() {
            return response.getStatusLine().getStatusCode();
        }
    }

    public static class Request {
        private Method method;
        private HttpRequestBase httpRequest;
        private HttpClient httpClient;

        public Request(Method method, String uri) throws IllegalArgumentException {
            this.method = method;
            this.httpClient = HttpClients.createDefault();
            switch(method){
                case POST:
                    this.httpRequest = new HttpPost(uri);
                    break;
                case GET:
                    this.httpRequest = new HttpGet(uri);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid method");
            }
        }

        public Request withHeader(String name, String value){
            httpRequest.addHeader(name, value);
            return this;
        }

        public Request withForm(List<NameValuePair> values) throws UnsupportedEncodingException {
            if(method == Method.POST){
                ((HttpPost)httpRequest).setEntity(new UrlEncodedFormEntity(values, "UTF-8"));
            }
            return this;
        }

        public Request timeout(int seconds){
            RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(seconds * 1000)
            .setConnectTimeout(seconds * 1000)
            .setSocketTimeout(seconds * 1000).build();
            httpRequest.setConfig(requestConfig);
            return this;
        }

        public Response send() throws IOException {
            return new Response(httpClient.execute(httpRequest));
        }
    }

    public static Request POST(String uri){
        return new Request(Method.POST, uri);
    }

    public static Request GET(String uri){
        return new Request(Method.GET, uri);
    }
}
