package com.ticketing.ticket;
import com.ticketing.common.BusinessException;
/** A refusal/unavailable response is never a reliable 202 receipt. */
public class AttemptAdmissionException extends BusinessException {
    public final String code;
    public AttemptAdmissionException(int status,String code,String message){super(status,message);this.code=code;}
    /** Sprint S4: 429/503 refusals are control flow under a flood; skip the stack walk (same status, code, message). */
    @Override public synchronized Throwable fillInStackTrace(){return this;}
}
