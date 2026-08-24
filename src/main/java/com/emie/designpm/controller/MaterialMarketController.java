package com.emie.designpm.controller;
import com.emie.designpm.entity.MaterialMarketItem;import com.emie.designpm.service.MaterialMarketService;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.http.ResponseEntity;import java.util.*;
@RestController @RequestMapping({"/api/material-market","/api/materials"})
public class MaterialMarketController{
 private final MaterialMarketService service; public MaterialMarketController(MaterialMarketService s){service=s;}
 private AuthController.AuthSession required(HttpServletRequest r){try{return session(r);}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"未登录");}}
 @GetMapping public List<MaterialMarketItem> list(HttpServletRequest r){required(r);return service.list();}
 @GetMapping("/{id}") public ResponseEntity<?> detail(@PathVariable Long id,HttpServletRequest r){required(r);return service.list().stream().filter(x->id.equals(x.getId())).findFirst().<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
 private AuthController.AuthSession session(HttpServletRequest r){
  String token=r.getHeader("X-Auth-Token");
  if(token==null||token.isBlank()){
   String authorization=r.getHeader("Authorization");
   if(authorization!=null&&authorization.startsWith("Bearer ")) token=authorization.substring(7);
  }
  if(token==null||token.isBlank()) throw new SecurityException("未登录");
  var s=AuthController.validateToken(token); if(s==null) throw new SecurityException("未登录"); return s;
 }
 @PostMapping public ResponseEntity<?> publish(@RequestBody Map<String,Object> body,HttpServletRequest req){var s=session(req);return ResponseEntity.ok(service.publish(body,s.userId()));}
 @PatchMapping("/{id}") public ResponseEntity<?> update(@PathVariable Long id,@RequestBody Map<String,Object> body,HttpServletRequest req){var s=session(req);return ResponseEntity.ok(service.update(id,body,s.userId()));}
 @PutMapping("/{id}") public ResponseEntity<?> updateCompat(@PathVariable Long id,@RequestBody Map<String,Object> body,HttpServletRequest req){var s=session(req);return ResponseEntity.ok(service.update(id,body,s.userId()));}
 @PostMapping("/{id}/withdraw") public ResponseEntity<?> withdraw(@PathVariable Long id,HttpServletRequest req){var s=session(req);return ResponseEntity.ok(service.withdraw(id,s.userId()));}
 @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable Long id,HttpServletRequest req){var s=session(req);service.delete(id,s.userId());return ResponseEntity.ok(Map.of("message","已删除"));}
 @PostMapping("/{id}/select") public ResponseEntity<?> select(@PathVariable Long id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest req){var s=session(req);return ResponseEntity.ok(service.select(id,s.userId(),s.role(),body==null?null:Objects.toString(body.get("plannerId"),null)));}
}
