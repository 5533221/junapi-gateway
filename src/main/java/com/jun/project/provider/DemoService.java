package com.jun.project.provider;

import java.util.concurrent.CompletableFuture;

/**
 * @author 27164
 * @version 1.0
 * @description: TODO
 * @date 2024/5/16 14:36
 */
public interface DemoService {

    String sayHello(String name);

    String sayHello2(String name);

    default CompletableFuture<String> sayHelloAsync(String name){

        return CompletableFuture.completedFuture(sayHello(name));
    }
}
