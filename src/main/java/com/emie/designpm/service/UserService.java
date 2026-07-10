package com.emie.designpm.service;

import com.emie.designpm.entity.User;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.controller.AuthController;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private final UserRepository userRepository;
    /** 用户信息缓存，频繁查询 userName 等操作 */
    private final Map<String, User> userCache = new ConcurrentHashMap<>();
    /** 角色用户列表缓存 */
    private final Map<String, List<User>> roleCache = new ConcurrentHashMap<>();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void initUsers() {
        if (userRepository.count() > 0) {
            // 启动时预加载所有用户到缓存
            refreshCache();
            return;
        }

        // 测试阶段每个角色只保留一个账号
        userRepository.save(User.builder().userId("admin_liu").name("刘海娇").role("admin").roleLevel(0).title("管理员").password(pwd("admin_liu")).build());
        userRepository.save(User.builder().userId("sales_sun").name("孙瑞婷").role("sales").roleLevel(1).title("销售").password(pwd("sales_sun")).build());
        userRepository.save(User.builder().userId("sales_cai").name("蔡小露").role("sales").roleLevel(1).title("销售").password(pwd("sales_cai")).build());
        userRepository.save(User.builder().userId("planner_zheng").name("郑诗绚").role("planner").roleLevel(2).title("产品企划").password(pwd("planner_zheng")).build());
        userRepository.save(User.builder().userId("planner_wu").name("吴思欣").role("planner").roleLevel(2).title("产品企划").password(pwd("planner_wu")).build());
        userRepository.save(User.builder().userId("designer_cheny").name("陈月珍").role("designer").roleLevel(3).title("设计师").password(pwd("designer_cheny")).build());
        userRepository.save(User.builder().userId("designer_huang").name("黄海岚").role("designer").roleLevel(3).title("设计师").password(pwd("designer_huang")).build());
        userRepository.save(User.builder().userId("supplychain_01").name("供应链01").role("supplychain").roleLevel(3).title("供应链").password(pwd("supplychain_01")).build());
        refreshCache();
    }

    private String pwd(String id) {
        return AuthController.sha256(id);
    }

    public List<User> getUsersByRole(String role) {
        // 用户管理、组织架构和管理员视角切换都要求读取最新数据。
        // 不能使用长期缓存，否则新增用户、角色变更或部门变更后，
        // /api/users 会继续返回旧的角色列表。
        List<User> users = userRepository.findByRole(role);
        roleCache.put(role, users);
        for (User user : users) {
            userCache.put(user.getUserId(), user);
        }
        return users;
    }

    public User getUserByUserId(String userId) {
        if (userId == null) return null;
        return userCache.computeIfAbsent(userId, id -> userRepository.findByUserId(id).orElse(null));
    }

    public String getUserName(String userId) {
        User u = getUserByUserId(userId);
        return u != null ? u.getName() : (userId != null ? userId : "未知");
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User saveUser(User user) {
        User saved = userRepository.save(user);
        refreshCache();
        return saved;
    }

    public List<User> getUsersByDepartmentId(Long departmentId) {
        return userRepository.findByDepartmentId(departmentId);
    }

    /** 刷新用户缓存（管理员添加用户后调用） */
    public void refreshCache() {
        List<User> all = userRepository.findAll();
        userCache.clear();
        roleCache.clear();
        for (User u : all) {
            userCache.put(u.getUserId(), u);
            roleCache.computeIfAbsent(u.getRole(), k -> new java.util.ArrayList<>()).add(u);
        }
    }

    public static String roleLabel(String role) {
        return switch (role) {
            case "sales" -> "需求方/销售";
            case "planner" -> "产品企划";
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "admin" -> "管理员";
            default -> role;
        };
    }
}
