package com.looyee.local.handler;

import com.looyee.local.NettyProperties;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.client.methods.*;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;

@Slf4j
public class NettyLocalHttpClientHandler extends SimpleChannelInboundHandler<HttpObject> {

    final CloseableHttpClient httpClient = HttpClients.custom().build();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject httpObject) throws Exception {
        if (httpObject instanceof HttpRequest) {
            FullHttpResponse response = invokeProxyHttp(httpObject);
            ctx.writeAndFlush(response);
        }
    }

    /**
     * 当前 NettyLocalHttpClientHandler为最后一个InboundHandler 需要加入exceptionCaught，否则会报错
     *
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    private FullHttpResponse invokeProxyHttp(HttpObject httpObject) {
        FullHttpResponse result = null;
        try {
            FullHttpRequest httpRequest = (FullHttpRequest) httpObject;
            HttpContent content = (HttpContent) httpObject;

            String serverHost = NettyProperties.BUNDLE.getString("netty.proxy.host");
            Integer serverPort = Integer.parseInt(NettyProperties.BUNDLE.getString("netty.proxy.port"));

            String uri = "http://" + serverHost + ":" + serverPort + httpRequest.uri();
            log.info("代理本地请求uri = {}", uri);

            HttpUriRequest uriRequest = null;
            HttpMethod method = httpRequest.method();
            Charset charset = CharsetUtil.UTF_8;

            // 处理请求方式
            if (HttpMethod.GET.equals(method)) {
                uriRequest = new HttpGet(uri);
            } else if (HttpMethod.POST.equals(method)) {
                HttpPost post = new HttpPost(uri);
                post.setEntity(new StringEntity(content.content().toString(charset)));
                uriRequest = post;
            } else if (HttpMethod.PUT.equals(method)) {
                HttpPut put = new HttpPut(uri);
                put.setEntity(new StringEntity(content.content().toString(charset)));
                uriRequest = put;
            } else if (HttpMethod.DELETE.equals(method)) {
                uriRequest = new HttpDelete(uri);
            }

            HttpHeaders headers = httpRequest.headers();
            Iterator<Map.Entry<String, String>> entryIterator = headers.iteratorAsString();
            boolean b = uriRequest != null;
            while (b && entryIterator.hasNext()) {
                Map.Entry<String, String> next = entryIterator.next();
                uriRequest.addHeader(next.getKey(), next.getValue());
            }
            if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
                if (b) {
                    uriRequest.removeHeaders("content-length");
                }
            }
            org.apache.http.HttpResponse response = httpClient.execute(uriRequest);
            if (response.getEntity() == null) {
                result = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK);
            } else {
                result = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(EntityUtils.toString(response.getEntity(), charset).getBytes(charset)));
            }
            Header[] allHeaders = response.getAllHeaders();
            for (Header header : allHeaders) {
                result.headers().set(header.getName(), header.getValue());
            }
        } catch (HttpHostConnectException e){
            String res = "本地应用程序不存在，请检查客户端本地代理，本地程序是否启动";
            log.error(res);
            result = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(res.getBytes(CharsetUtil.UTF_8)));
            result.headers().set(HttpHeaderNames.CONTENT_LENGTH, result.content().readableBytes());
            result.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
        } catch (Exception e) {
            log.error("", e);
        }
        return result;
    }

}
