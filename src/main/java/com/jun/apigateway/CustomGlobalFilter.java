package com.jun.apigateway;


import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import com.junapicommon.model.entity.InterfaceInfo;
import com.junapicommon.model.entity.User;
import com.junapicommon.service.InnerInterfaceInfoService;
import com.junapicommon.service.InnerUserInterfaceInfoService;
import com.junapicommon.service.InnerUserService;
import com.sdk.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {


    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;
    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;
    @DubboReference
    private InnerUserService innerUserService;

    public static final List<String> IP_WHITE_LIST= Arrays.asList("127.0.0.1");


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {


        //1.请求的统一的日志
        ServerHttpRequest request = exchange.getRequest();

        String path = request.getPath().value();
        //todo 加上请求的ip
        String url="http://localhost:8123"+path;
        String method = request.getMethod().toString();
        log.info("请求唯一标识===============》"+request.getId());
        log.info("请求路径===============》"+path);
        log.info("请求方法===============》"+method);
        log.info("请求头===============》"+request.getHeaders().values());
        log.info("请求来源地址===============》"+request.getRemoteAddress());
        String hostString = request.getRemoteAddress().getHostString();
        log.info("请求来源地址===============》"+hostString);
        log.info("请求参数===============》"+request.getQueryParams());


        ServerHttpResponse response = exchange.getResponse();

        //2.黑白名单
        if(!IP_WHITE_LIST.contains(hostString)){

          return  handlerError(response);
        }

        //3.用户的鉴权 (ak/sk)
        //先获取请求头
        HttpHeaders headers = request.getHeaders();
        //获取请求头中的信息
        String accessKey = headers.getFirst("accessKey");
        String body = null ;
        //解决乱码   服务端这边进行对body的 解码   decode
        try {
            body= URLDecoder.decode( headers.getFirst("body"),"utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        String sign = headers.getFirst("sign");
        String nonce = headers.getFirst("nonce");
        String timestamp =  headers.getFirst("timestamp");


        //todo 实际情况根据数据库查询是否已经分配给用户
        User user =null;

        try {
            user=  innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            //捕获异常
          log.error("getInvokeUser",e);
        }

        if(user == null){
            //如果用户信息为空 处理未授权
            return handlerErrorNoAuth(response);
        }
        //获取当前用户的accesskey
        String userAccessKey = user.getAccessKey();

        if(! userAccessKey.equals(accessKey) ){

           return handlerErrorNoAuth(response);
        }
        if((long) nonce.length() >10000){

            return handlerErrorNoAuth(response);
        }

        //防止重放问题 比如今天的访问 明天还能访问  使用时间戳的方式
//        String oldTime="1714902695179";

        //前端传来的时间戳
        Date date = new Date(Long.parseLong(timestamp));
        //当前时间
        Date date1 = new Date(System.currentTimeMillis()/1000);
        //相差多少分钟  如果超过了 则无权限
        long offsetTime = DateUtil.between(date, date1, DateUnit.MINUTE);
        System.out.println("相差的时间为："+offsetTime);
        //超过十分钟  表示重发  设置为无权限
        if(offsetTime > 10L){

            throw new RuntimeException("超出时间,暂无权限");

        }



        //todo 实际情况需要查询数据库 secretKey
        //获取当前调用接口的用户的secretKey
        String userSecretKey = user.getSecretKey();
        //使用获取的秘钥对请求体进行签名
        String key = SignUtils.getSign(body, userSecretKey);

        if(sign== null || !sign.equals(key)){

            //如果签名为空 或者不一致则返回处理未授权响应
            return handlerErrorNoAuth(response);

        }


        //4.请求模拟接口是否存在
        //todo 从数据库中查询模拟接口是否存在 以及请求方法是否匹配(还可以校验请求参数)
        InterfaceInfo interfaceInfo=null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(url, method);
        } catch (Exception e) {
            //捕获异常
           log.error("getInterfaceInfo",e);
        }

        //5.请求转发 调用模拟接口
//        Mono<Void> filter = chain.filter(exchange);

       return handleResponse(exchange, chain,interfaceInfo.getId(),user.getId());

    }
    //响应
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain,long interfaceInfoId, long userId){
        try {
            //获取响应
            ServerHttpResponse originalResponse = exchange.getResponse();
            //响应工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            //获取状态码
            HttpStatus statusCode = originalResponse.getStatusCode();


            if (statusCode != HttpStatus.OK) {
                return chain.filter(exchange);//降级处理返回数据
            }
            //装饰器模式 在原本的类的基础上对其能力进行增强。
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                // 重写writeWith方法，用于处理响应体的数据
                // 这段方法就是只要当我们的模拟接口调用完成之后,等它返回结果，
                // 就会调用writeWith方法,我们就能根据响应结果做一些自己的处理
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    // 判断响应体是否是Flux类型
                    if (body instanceof Flux) {

                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);

                        // (这里就理解为它在拼接字符串,它把缓冲区的数据取出来，一点一点拼接好)
                        return super.writeWith(fluxBody.buffer()
                                .map(dataBuffers -> {

                                    //todo 调用成功  接口调用数据+1 invokeAddCount()
                                    try {
                                        innerUserInterfaceInfoService.invokeAddCount(interfaceInfoId,userId);
                                    } catch (Exception e) {
                                        //捕获异常
                                       log.error("invokeAddCount",e);
                                    }

                                    // 合并多个流集合，解决返回体分段传输
                            DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
                            DataBuffer buff = dataBufferFactory.join(dataBuffers);
                            byte[] content = new byte[buff.readableByteCount()];

                            buff.read(content);
                            DataBufferUtils.release(buff);//释放掉内存
                            // 构建返回日志
                            String joinData = new String(content);
                            //打印日志
                            log.info("响应的结果："+ joinData);

                            //设置长度
                            getDelegate().getHeaders().setContentLength(joinData.getBytes().length);
                            return bufferFactory.wrap(joinData.getBytes());
                        }));
                    } else {
                        log.error("响应code异常"+getStatusCode());
                    }
                    return super.writeWith(body);
                }
            };
            return chain.filter(exchange.mutate().response(decoratedResponse).build());

        } catch (Exception e) {
            log.error("网关处理日志异常.\n" + e);
            return chain.filter(exchange);
        }
    }



    @Override
    public int getOrder() {
        return -1;
    }

    public Mono<Void> handlerError( ServerHttpResponse response){

        //响应错误信息
        response.setStatusCode(HttpStatus.FAILED_DEPENDENCY);

        return response.setComplete();
    }


    public Mono<Void> handlerErrorNoAuth( ServerHttpResponse response){

        //响应错误信息  无权限
        response.setStatusCode(HttpStatus.FORBIDDEN);

        return response.setComplete();
    }


}

