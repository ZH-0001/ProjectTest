package cn.edu.studyroom.api;
import java.util.*;

public record Api<T>(String code, String message, T data) {
    public static <T> Api<T> ok(T data) { return new Api<>("OK","成功",data); }
    public static Api<Object> error(String code,String message) { return new Api<>(code,message,null); }
    public static Map<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static final class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status,String code,String message) { super(message); this.status=status; this.code=code; }
    }
    public static void require(boolean condition,int status,String code,String message) {
        if(!condition) throw new Failure(status,code,message);
    }
}
