package io.depradar.web;

import io.depradar.identity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PageController {
    private final Access access;private final String mode;
    public PageController(Access access,@Value("${depradar.osv-mode}") String mode) {this.access=access;this.mode=mode;}
    @GetMapping("/") public String home(Model model) {return app(model,"home","");}
    @GetMapping("/projects/{id}") public String project(@PathVariable String id,Model model) {access.project(id,Access.Right.READ);return app(model,"project",id);}
    @GetMapping("/releases/{id}") public String release(@PathVariable String id,Model model) {access.release(id,Access.Right.READ);return app(model,"release",id);}
    @GetMapping("/scans/{id}") public String scan(@PathVariable String id,Model model) {access.human();return app(model,"scan",id);}
    private String app(Model model,String view,String id) {model.addAttribute("view",view);model.addAttribute("resourceId",id);model.addAttribute("mode",mode);return "app";}
}
