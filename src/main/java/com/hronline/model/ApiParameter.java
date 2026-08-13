package com.hronline.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * API参数数据模型 - 表示接口的单个入参
 *
 * 记录从源码注解中解析出的参数元信息，包括参数名、类型、位置、
 * 默认值、是否必填等。用于构建HTTP请求和生成测试数据。
 */
public class ApiParameter {

    /** 参数名称（Java变量名或注解中指定的别名） */
    private String name = "";
    /** Java类型名称（如 String、Integer、UserDTO） */
    private String type = "String";
    /** 参数在HTTP请求中的位置（路径/查询/请求体/请求头） */
    private ParameterLocation location = ParameterLocation.QUERY;
    /** 是否必填（对应注解中的 required 属性） */
    private boolean required = true;
    /** 默认值（对应注解中的 defaultValue 属性） */
    private String defaultValue = "";
    /** 参数描述（取自Javadoc @param 或 @ApiModelProperty） */
    private String description = "";
    /** 示例值（取自@ApiModelProperty.example 或 @Example） */
    private String example = "";
    /** 子参数列表（当类型为复杂对象时，递归解析其字段） */
    private List<ApiParameter> children = new ArrayList<>();
    /** 是否为文件上传参数（@RequestPart + MultipartFile），需以 multipart/form-data 提交 */
    private boolean file = false;

    public ApiParameter() {}

    public ApiParameter(String name, String type, ParameterLocation location, boolean required,
                        String defaultValue, String description, String example,
                        List<ApiParameter> children) {
        this.name = name != null ? name : "";
        this.type = type != null ? type : "String";
        this.location = location != null ? location : ParameterLocation.QUERY;
        this.required = required;
        this.defaultValue = defaultValue != null ? defaultValue : "";
        this.description = description != null ? description : "";
        this.example = example != null ? example : "";
        this.children = children != null ? children : new ArrayList<>();
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public ParameterLocation getLocation() { return location; }
    public void setLocation(ParameterLocation location) { this.location = location; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExample() { return example; }
    public void setExample(String example) { this.example = example; }

    public List<ApiParameter> getChildren() { return children; }
    public void setChildren(List<ApiParameter> children) { this.children = children; }

    public boolean isFile() { return file; }
    public void setFile(boolean file) { this.file = file; }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 判断参数是否为复杂类型（包含子字段）
     */
    public boolean isComplexType() {
        return !children.isEmpty();
    }

    /**
     * 获取参数类型的简要描述
     */
    public String typeDisplay() {
        return isComplexType() ? type + " {...}" : type;
    }

    /**
     * 根据类型和字段名生成合理的默认测试值
     * 当AI服务不可用时，作为降级方案使用
     */
    public String generateDefaultValue() {
        // 文件上传参数：无文本默认值，需通过文件选择器指定路径
        if (file) return "";
        if (example != null && !example.isBlank()) return example;
        if (defaultValue != null && !defaultValue.isBlank()) return defaultValue;

        // 根据字段名智能生成真实可用的值
        String lowerName = name.toLowerCase();
        
        // 邮箱字段
        if (lowerName.contains("email") || lowerName.contains("mail")) {
            return "zhangsan@example.com";
        }
        // 手机号字段
        if (lowerName.contains("phone") || lowerName.contains("mobile")) {
            return "13800138000";
        }
        // 用户名字段
        if (lowerName.contains("username") || lowerName.contains("user_name")) {
            return "zhangsan";
        }
        // 密码字段
        if (lowerName.contains("password") || lowerName.contains("pwd")) {
            return "Abc@123456";
        }
        // ID字段
        if (lowerName.equals("id") || lowerName.endsWith("_id") || lowerName.endsWith("Id")) {
            return "1001";
        }
        // 名称字段
        if (lowerName.contains("name") || lowerName.contains("title")) {
            return "张三";
        }
        // 描述字段
        if (lowerName.contains("desc") || lowerName.contains("description") || lowerName.contains("remark")) {
            return "这是一段描述信息";
        }
        // 地址字段
        if (lowerName.contains("address") || lowerName.contains("location")) {
            return "北京市朝阳区建国路100号";
        }
        // 时间戳字段
        if (lowerName.contains("timestamp") || lowerName.contains("time_stamp")) {
            return String.valueOf(System.currentTimeMillis());
        }
        // URL字段
        if (lowerName.contains("url") || lowerName.contains("link")) {
            return "https://www.example.com";
        }
        // 状态字段
        if (lowerName.contains("status") || lowerName.contains("state")) {
            return "1";
        }
        // 类型字段
        if (lowerName.contains("type") || lowerName.contains("category")) {
            return "default";
        }
        // 编码字段
        if (lowerName.contains("code") || lowerName.contains("no") || lowerName.contains("number")) {
            return "A10001";
        }
        // 年龄字段
        if (lowerName.contains("age")) {
            return "25";
        }
        // 金额/价格字段
        if (lowerName.contains("price") || lowerName.contains("amount") || lowerName.contains("money")) {
            return "99.90";
        }

        // 根据Java类型生成值
        String lowerType = type.toLowerCase();
        return switch (lowerType) {
            case "string", "java.lang.string" -> generateStringDefault();
            case "int", "integer", "java.lang.integer" -> "1";
            case "long", "java.lang.long" -> "100";
            case "double", "java.lang.double" -> "1.0";
            case "float", "java.lang.float" -> "1.0";
            case "boolean", "java.lang.boolean" -> "true";
            case "bigdecimal", "java.math.bigdecimal" -> "100.00";
            case "date", "java.util.date" -> "2025-01-15";
            case "localdate", "java.time.localdate" -> "2025-01-15";
            case "localdatetime", "java.time.localdatetime" -> "2025-01-15T10:30:00";
            case "uuid", "java.util.uuid" -> "550e8400-e29b-41d4-a716-446655440000";
            case "list", "arraylist", "java.util.list" -> "[]";
            case "map", "hashmap", "java.util.map" -> "{}";
            default -> isComplexType() ? generateComplexDefault() : generateStringDefault();
        };
    }

    /**
     * 根据字段名生成合理的字符串默认值（避免test_xxx占位符）
     */
    private String generateStringDefault() {
        String lowerName = name.toLowerCase();
        if (lowerName.contains("content") || lowerName.contains("body") || lowerName.contains("text")) {
            return "这是一段内容";
        }
        if (lowerName.contains("comment") || lowerName.contains("note")) {
            return "这是一条备注";
        }
        if (lowerName.contains("tag") || lowerName.contains("label")) {
            return "标签";
        }
        if (lowerName.contains("path") || lowerName.contains("file")) {
            return "/data/upload/file.txt";
        }
        if (lowerName.contains("image") || lowerName.contains("avatar") || lowerName.contains("photo")) {
            return "https://www.example.com/image.jpg";
        }
        if (lowerName.contains("key") || lowerName.contains("token")) {
            return "abc123def456";
        }
        // 通用默认值：用字段名驼峰形式作为值
        return name;
    }

    /**
     * 为复杂类型生成JSON格式的默认值
     */
    private String generateComplexDefault() {
        StringJoiner joiner = new StringJoiner(", ");
        for (ApiParameter child : children) {
            joiner.add("\"" + child.name + "\": " + wrapValue(child.generateDefaultValue(), child.type));
        }
        return "{" + joiner + "}";
    }

    /**
     * 根据类型包装值（字符串加引号，数字/布尔不加）
     */
    private String wrapValue(String value, String type) {
        String lowerType = type.toLowerCase();
        return switch (lowerType) {
            case "int", "integer", "long", "double", "float", "boolean",
                 "java.lang.integer", "java.lang.long", "java.lang.double",
                 "java.lang.float", "java.lang.boolean" -> value;
            default -> (value.startsWith("{") || value.startsWith("[")) ? value : "\"" + value + "\"";
        };
    }

    // ================================================================
    // equals / hashCode / toString
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiParameter that = (ApiParameter) o;
        return required == that.required &&
                file == that.file &&
                Objects.equals(name, that.name) &&
                Objects.equals(type, that.type) &&
                location == that.location &&
                Objects.equals(defaultValue, that.defaultValue) &&
                Objects.equals(description, that.description) &&
                Objects.equals(example, that.example) &&
                Objects.equals(children, that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, location, required, file, defaultValue, description, example, children);
    }

    @Override
    public String toString() {
        return "ApiParameter{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", location=" + location +
                ", required=" + required +
                ", file=" + file +
                ", defaultValue='" + defaultValue + '\'' +
                ", description='" + description + '\'' +
                ", example='" + example + '\'' +
                ", children=" + children +
                '}';
    }
}