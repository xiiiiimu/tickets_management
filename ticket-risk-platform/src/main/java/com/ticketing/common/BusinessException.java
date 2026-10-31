package com.ticketing.common;
public class BusinessException extends RuntimeException {
 public final int status;
 public BusinessException(int status,String message){super(message);this.status=status;}
 public static void require(boolean test,String message){if(!test)throw new BusinessException(409,message);}
}
