package cn.edu.studyroom.security;
import java.io.Serializable;
public record CurrentUser(long id,String account,String name,String role) implements Serializable {}
