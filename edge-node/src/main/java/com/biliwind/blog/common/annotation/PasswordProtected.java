package com.biliwind.blog.common.annotation;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.*;

@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PasswordProtected {
}
