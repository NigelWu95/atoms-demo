package dev.atoms.demo.instances;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.stereotype.Service;

/** Owner-only capability adapter, NOT a general reverse proxy. Standalone cookies stay server-side. */
@Service
public class RuntimeAccessService {
    private final InstanceService instances;
    private final RuntimeEngine engine;
    private final ObjectMapper json;
    private final RuntimeHttp http=new RuntimeHttp();
    public RuntimeAccessService(InstanceService instances,RuntimeEngine engine,ObjectMapper json){this.instances=instances;this.engine=engine;this.json=json;}
    byte[] access(UUID owner,String project,String id,String resource,JsonNode body,String key,HttpSession session){
        var instance=instances.get(owner,project,id);
        var process=engine.require(instance);
        String path=switch(resource){case "app"->"/api/app";case "document"->"/api/app/document";case "state"->"/api/state";default->throw InstanceService.missing();};
        byte[] request=null;
        if(body!=null){
            if(!resource.equals("state") || !body.isObject() || body.size()!=4
                    || !instance.loadedRuntimeId().equals(body.path("loadedRuntimeId").asText())
                    || key==null || !key.matches("[A-Za-z0-9._:-]{1,128}"))throw new ApiException(422,"INVALID_INPUT","独立应用保存参数无效");
            try{request=json.writeValueAsBytes(body);}catch(Exception error){throw new ApiException(422,"INVALID_INPUT","独立应用保存参数无效");}
            if(request.length>320*1024)throw new ApiException(413,"PAYLOAD_TOO_LARGE","状态超出大小限制");
        }
        synchronized(process){
            engine.require(instances.get(owner,project,id));
            String attribute="atoms.runtime.session."+process.slot.id();
            // Concurrent HTTP requests can carry older JDBC-session attribute snapshots.
            // The owner-only process monitor supplies one bootstrap identity to all of them.
            String cookie=process.sessionCookie!=null?process.sessionCookie:
                    session.getAttribute(attribute) instanceof String value?value:null;
            // Check identity before sending credentials to a loopback listener that may have changed.
            requireOk(send(process,"/api/health","GET",null,Map.of()));
            if(cookie==null){
                var token=send(process,"/api/csrf","GET",null,Map.of());requireOk(token);
                String initial=token.cookie();if(initial==null)throw RuntimeHttp.unavailable();
                var initialized=send(process,"/api/session","POST","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Map.of("Cookie",initial,"X-CSRF-TOKEN",csrf(token)));
                requireOk(initialized);cookie=initialized.cookie()==null?initial:initialized.cookie();
            }
            process.sessionCookie=cookie;session.setAttribute(attribute,cookie);
            var headers=new HashMap<String,String>();headers.put("Cookie",cookie);
            if(request!=null){
                var token=send(process,"/api/csrf","GET",null,headers);requireOk(token);
                if(token.cookie()!=null){headers.put("Cookie",token.cookie());process.sessionCookie=token.cookie();session.setAttribute(attribute,token.cookie());}
                headers.put("X-CSRF-TOKEN",csrf(token));headers.put("Idempotency-Key",key);
            }
            var response=send(process,path,request==null?"GET":"PUT",request,headers);
            requireOk(response);
            if(response.cookie()!=null){process.sessionCookie=response.cookie();session.setAttribute(attribute,response.cookie());}
            if(resource.equals("document")){
                if(!response.type().toLowerCase(Locale.ROOT).startsWith("text/html"))throw RuntimeHttp.unavailable();
            }else{
                if(!response.type().toLowerCase(Locale.ROOT).startsWith("application/json"))throw RuntimeHttp.unavailable();
                JsonNode value=parse(response);
                if(resource.equals("app") && !instance.loadedRuntimeId().equals(value.path("loadedRuntimeId").asText()))throw RuntimeHttp.unavailable();
            }
            return response.bytes();
        }
    }
    private RuntimeHttp.Reply send(RuntimeProcess process,String path,String method,byte[] body,Map<String,String> headers){
        return http.send(process.slot.port(),process.id,process.slot.id(),path,method,body,headers);
    }
    private JsonNode parse(RuntimeHttp.Reply response){
        try{return json.readTree(response.bytes());}catch(Exception error){throw RuntimeHttp.unavailable();}
    }
    private String csrf(RuntimeHttp.Reply response){
        var value=parse(response);String token=value.path("token").asText();
        if(!"X-CSRF-TOKEN".equals(value.path("headerName").asText()) || !token.matches("[A-Za-z0-9_+/=-]{16,256}"))throw RuntimeHttp.unavailable();
        return token;
    }
    private void requireOk(RuntimeHttp.Reply response){
        if(response.status()==200)return;
        String code=parse(response).path("error").path("code").asText();
        if(response.status()==409 && Set.of("STATE_CONFLICT","STALE_APP_VERSION").contains(code))
            throw new ApiException(409,code,"应用数据或版本已变化，请在应用内重新读取后操作");
        if(response.status()==422 && Set.of("INVALID_INPUT","IDEMPOTENCY_PAYLOAD_MISMATCH").contains(code))
            throw new ApiException(422,code,"独立应用拒绝了无效或不一致的保存操作");
        throw RuntimeHttp.unavailable();
    }
}
