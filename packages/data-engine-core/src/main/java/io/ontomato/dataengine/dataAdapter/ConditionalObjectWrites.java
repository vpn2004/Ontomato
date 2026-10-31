package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;

/** Explicit optimistic writes. Hosts must resolve the principal from authenticated server state. */
public interface ConditionalObjectWrites {
    record Principal(String subject, List<String> roles) {
        public Principal {
            if (subject == null || !subject.matches("[A-Za-z0-9._:@/-]{1,200}") || roles == null || roles.isEmpty()
                || roles.stream().anyMatch(r -> r == null || !r.matches("[A-Za-z0-9._:-]{1,80}")))
                throw new IllegalArgumentException("Conditional writes require a trusted subject and explicit roles");
            roles = roles.stream().distinct().sorted().toList();
        }
    }

    /** Returns attributes, version and the opaque strong ETag for the caller's subsequent decision. */
    JSONObject readVersionedObject(String className, String id, Principal principal) throws Exception;

    /** Exact-object PATCH; never turns a stale decision into an unconditional write or retries with a fresh ETag. */
    JSONObject patchObject(String className, String id, JSONObject values, String expectedEtag,
                           String idempotencyKey, Principal principal) throws Exception;
}
