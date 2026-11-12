package com.biliwind.blog.context;

import com.biliwind.blog.service.PageIdentity;
import com.biliwind.blog.service.PageIdentityService;
import jakarta.enterprise.context.RequestScoped;

/**
 * Holds the page identity resolved by the controller. Qute globals and the
 * response filter consume it; when a controller does not resolve one, the
 * first reader derives it from the request path and the rest reuse the result.
 */
@RequestScoped
public class PageIdentityContext {

    private PageIdentity identity;

    public PageIdentity get() {
        return identity;
    }

    public void set(PageIdentity identity) {
        this.identity = identity;
    }

    public PageIdentity resolve(PageIdentityService service, String requestPath, String language) {
        PageIdentity current = identity;
        if (current == null) {
            current = service.page(requestPath, language);
            identity = current;
        }
        return current;
    }
}
