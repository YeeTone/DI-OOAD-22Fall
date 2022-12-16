package dependency_injection;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Enjoy DI, Enjoy OOAD!
public class BeanFactoryImpl implements BeanFactory {
    private final Map<Class<?>, Class<?>> injectBaseMap = new HashMap<>();
    private final Map<String, String> injectValueMap = new HashMap<>();

    private final Map<Class<?>, Object> simpleTypeDefaultValues = new HashMap<Class<?>, Object>() {{
        put(boolean.class, false); //设置默认值
        put(Boolean.class, false);
        put(int.class, 0);
        put(Integer.class, 0);
    }};

    @Override
    public void loadInjectProperties(File file) {
        // 直接拿了去年我自己AC的代码
        try (FileInputStream fis = new FileInputStream(file)) {
            Properties injectProperties = new Properties();
            injectProperties.load(fis);
            injectBaseMap.clear();
            for (Object o : injectProperties.keySet()) {
                Class<?> abstractClazz = Class.forName(o.toString());
                Class<?> implementClazz = Class.forName(injectProperties.get(o).toString());
                injectBaseMap.put(abstractClazz, implementClazz);
            }
            // 加载抽象类和对应的实现类
        } catch (Throwable e) {
            e.printStackTrace();
        }

    }

    @Override
    public void loadValueProperties(File file) {
        // 直接拿了去年我自己AC的代码
        try (FileInputStream fis = new FileInputStream(file)) {
            Properties valueProperties = new Properties();
            valueProperties.load(fis);
            injectValueMap.clear();
            for (Object o : valueProperties.keySet()) {
                String fieldName = o.toString();
                String fieldValue = valueProperties.get(o).toString();
                injectValueMap.put(fieldName, fieldValue);
            }
            // 加载Value对应的字符串
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public <T> T createInstance(Class<T> clazz) {
        // 以下部分来自21秋季学期实验课
        try {
            //1. 找实现类implementClazz
            Class<?> implementClazz = injectBaseMap.getOrDefault(clazz, clazz);

            //2.通过实现类找constructor
            //  2.1 找带有inject注解的
            //  2.2 没找到，就找自带的无参构造方法;
            Constructor<?> injectedConstructor = findAnnotatedOrDefaultConstructor(implementClazz);

            //3. 通过constructor，获取所有parameters
            Parameter[] parameters = injectedConstructor.getParameters();
            //4. 创建一个Object[], 为了存放每个parameter的实例；
            Object[] parameterInstances = new Object[parameters.length];
            //5. 遍历每一个parameter，分别创建实例放入Object[]
            buildConstructorParameters(parameters, parameterInstances);
            //6. 根据Object[]，以及constructor，创建impleClazz实例
            T tInstance = (T) injectedConstructor.newInstance(parameterInstances);
            // --------- 创建好了实例 -----------
            //7. 找当前ImplementClazz的所有field，遍历每一个field
            buildInjectFields(tInstance);
            return tInstance;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Constructor<?> findAnnotatedOrDefaultConstructor(Class<?> implementClazz) throws NoSuchMethodException {
        //找到@Inject注解修饰的构造器
        // 没找到，就使用默认的无参构造器
        Optional<Constructor<?>> optional = Arrays.stream(implementClazz.getDeclaredConstructors())
                .filter(e -> e.isAnnotationPresent(Inject.class))
                .findFirst();
        if(optional.isPresent()){
            return optional.get();
        }else {
            return implementClazz.getDeclaredConstructor();
        }
    }

    private Object buildParameter(Parameter parameter) throws Exception {
        if (parameter.isAnnotationPresent(Value.class)) {
            Value valueAnno = parameter.getAnnotation(Value.class);
            Class<?> type = parameter.getType();
            if (is3SimpleTypes(type)) { // 判断是否是boolean, int或者String三者之一
                return build3SimpleClassObject(valueAnno, type);
            } else if (type.isArray()) { // 判断是否是方括号数组
                return build3SimpleClassArray(valueAnno, type);
            } else if (type.equals(List.class)) { // 判断是否是列表
                Class<?> elementType =(Class<?>) ((ParameterizedType) parameter.getParameterizedType())
                        .getActualTypeArguments()[0];
                // 此处必须通过Parameter拿到对应的泛型信息，通过Class信息则无法拿到
                return build3SimpleClassList(valueAnno, elementType);
            } else if (type.equals(Set.class)) {
                Class<?> elementType =(Class<?>)  ((ParameterizedType) parameter.getParameterizedType())
                        .getActualTypeArguments()[0];
                return build3SimpleClassSet(valueAnno, elementType);
            } else if (type.equals(Map.class)) {
                Class<?> keyType = Class.forName(((ParameterizedType) parameter.getParameterizedType())
                        .getActualTypeArguments()[0].getTypeName());
                Class<?> valueType = Class.forName(((ParameterizedType) parameter.getParameterizedType())
                        .getActualTypeArguments()[1].getTypeName());
                return build3SimpleClassMap(valueAnno, keyType, valueType);
            } else {
                return null;
            }
        } else {
            Class<?> type = parameter.getType();
            return createInstance(type);
            // 如果没有@Value修饰，那么通过createInstance递归创建对象
        }
    }

    private void buildConstructorParameters(Parameter[] parameters, Object[] parameterInstances) throws Exception {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] != null) {
                parameterInstances[i] = buildParameter(parameters[i]);
            }
        }
    }

    private boolean is3SimpleTypes(Class<?> type) {
        return type.equals(boolean.class) || type.equals(Boolean.class)
                || type.equals(int.class) || type.equals(Integer.class)
                || type.equals(String.class);
    }

    private Object build3SimpleClassObject(Value valueAnno, Class<?> simpleType) {
        String[] split;

        if (injectValueMap.containsKey(valueAnno.value())) {
            split = injectValueMap.get(valueAnno.value()).split(valueAnno.delimiter());
        } else {
            split = valueAnno.value().split(valueAnno.delimiter());
        }
        // 判断是否能用value.properties做替代
        // 注意这个替代只能用一次，用多次了可能会出现问题

        for (String s : split) {
            if (simpleType.equals(boolean.class) || simpleType.equals(Boolean.class)) {
                if (SimpleParser.isAccepted(simpleType, s)) { // 检查是否符合对应的类型要求
                    return Boolean.parseBoolean(s);
                }
            } else if (simpleType.equals(int.class) || simpleType.equals(Integer.class)) {
                if (SimpleParser.isAccepted(simpleType, s)) {
                    return Integer.parseInt(s);
                }
            } else if (simpleType.equals(String.class)) {
                if (SimpleParser.isAccepted(simpleType, s)) {
                    return s;
                }
            }
        }

        return simpleTypeDefaultValues.get(simpleType);
    }

    private Object build3SimpleClassArray(Value valueAnno, Class<?> arrayType) {
        Class<?> elementType = arrayType.getComponentType();

        String[] elementStrings;

        if (injectValueMap.containsKey(valueAnno.value())) {
            elementStrings = injectValueMap.get(valueAnno.value()).substring(1, injectValueMap.get(valueAnno.value()).length() - 1)
                    .split(valueAnno.delimiter());
        } else {
            elementStrings = valueAnno.value().substring(1, valueAnno.value().length() - 1)
                    .split(valueAnno.delimiter());
        }

        elementStrings = Arrays.stream(elementStrings).filter(e -> !e.isEmpty()).toArray(String[]::new);
        // 判断是否能用value.properties做替代
        // 注意这个替代只能用一次（根据题目的要求）
        // 因此List，Set利用这个方法前，都没有做替代操作
        // 注意去除两端的括号
        if (elementType.equals(boolean.class) || elementType.equals(Boolean.class)) {
            List<Boolean> booleanList = new ArrayList<>();
            for (String s : elementStrings) {
                if (SimpleParser.isAccepted(boolean.class, s)) {
                    booleanList.add(Boolean.parseBoolean(s));
                }
            }
            if(elementType.equals(boolean.class)){
                boolean[] arr = new boolean[booleanList.size()];
                for (int i = 0; i < booleanList.size(); i++) {
                    arr[i] = booleanList.get(i);
                }
                return arr;
            }else {
                return booleanList.toArray(new Boolean[0]);
            }
        } else if (elementType.equals(int.class) || elementType.equals(Integer.class)) {
            List<Integer> integerList = new ArrayList<>();
            for (String s : elementStrings) {
                if (SimpleParser.isAccepted(int.class, s)) {
                    integerList.add(Integer.parseInt(s));
                }
            }
            if(elementType.equals(int.class)){
                int[] arr = new int[integerList.size()];
                for (int i = 0; i < integerList.size(); i++) {
                    arr[i] = integerList.get(i);
                }
                return arr;
            }else {
                return integerList.toArray(new Integer[0]);
            }
        } else if (elementType.equals(String.class)) {
            return Arrays.asList(elementStrings).toArray();
        } else {
            return null;
        }
    }

    private Class<?> toArrayClass(Class<?> clazz) {
        Class<?> arrayClass = Object.class;
        if (clazz.equals(boolean.class) || clazz.equals(Boolean.class)) {
            arrayClass = boolean[].class;
        } else if (clazz.equals(int.class) || clazz.equals(Integer.class)) {
            arrayClass = int[].class;
        } else if (clazz.equals(String.class)) {
            arrayClass = String[].class;
        }

        return arrayClass;
    }

    private List<Object> build3SimpleClassList(Value valueAnno, Class<?> listElementType) {
        Class<?> arrayType = toArrayClass(listElementType);
        Object array = build3SimpleClassArray(valueAnno, arrayType);

        // 直接调用Array的构造方法，然后再依次放入结果的List中即可

        List<Object> resultList = new ArrayList<>();
        for (int i = 0, length = Array.getLength(array); i < length; i++) {
            resultList.add(Array.get(array, i));
        }

        return resultList;
    }

    private Set<Object> build3SimpleClassSet(Value valueAnno, Class<?> setElementType) {
        Class<?> arrayType = toArrayClass(setElementType);
        Object array = build3SimpleClassArray(valueAnno, arrayType);

        // 直接调用Array的构造方法，然后再依次放入结果的Set中即可

        Set<Object> resultSet = new HashSet<>();
        for (int i = 0, length = Array.getLength(array); i < length; i++) {
            resultSet.add(Array.get(array, i));
        }

        return resultSet;
    }

    private Map<Object, Object> build3SimpleClassMap(Value valueAnno, Class<?> keyType, Class<?> valueType) {
        String[] entryStrings;

        if (injectValueMap.containsKey(valueAnno.value())) {
            entryStrings = injectValueMap.get(valueAnno.value()).substring(1, injectValueMap.get(valueAnno.value()).length() - 1)
                    .split(valueAnno.delimiter());
        } else {
            entryStrings = valueAnno.value().substring(1, valueAnno.value().length() - 1).split(valueAnno.delimiter());
        }
        // 此处注意要做元素的可能替代，另外还要去除两侧的空格

        Map<Object, Object> resultMap = new HashMap<>();

        for (String e : entryStrings) {
            // e是Map.Entry的字符串修饰，格式是key:value，因此还要做一次分割
            // 分割后要检查是否符合类型要求
            String[] kv = e.split(":");
            String k = kv[0], v = kv[1];
            if (SimpleParser.isAccepted(keyType, k) && SimpleParser.isAccepted(valueType, v)) {
                Object key = SimpleParser.getInstance(keyType).parse(k);
                Object value = SimpleParser.getInstance(valueType).parse(v);
                resultMap.put(key, value);
            }
        }

        return resultMap;

    }

    private <T> void buildInjectFields(T tInstance) {
        Field[] fields = tInstance.getClass().getDeclaredFields();
        try {
            Arrays.stream(fields).filter(f -> f.isAnnotationPresent(Inject.class)).forEach(f -> {
                try {
                    //如果是用户自定义类且带有Inject注解，则递归调用createInstance
                    Object o = createInstance(f.getType());
                    f.setAccessible(true);
                    f.set(tInstance, o);
                    f.setAccessible(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            Arrays.stream(fields).filter(f -> f.isAnnotationPresent(Value.class)).forEach(f -> {
                try {
                    //如果是基本数据类型+String且带有@Value注解，按照value注解注入
                    Value valueAnno = f.getAnnotation(Value.class);
                    Object o = buildFieldObject(valueAnno, f);
                    f.setAccessible(true);
                    f.set(tInstance, o);
                    f.setAccessible(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Object buildFieldObject(Value valueAnno, Field field) throws ClassNotFoundException {
        if (field == null || valueAnno == null) {
            return null;
        }
        Class<?> type = field.getType();

        if (is3SimpleTypes(type)) {
            return build3SimpleClassObject(valueAnno, type);
        } else if (type.isArray()) {
            return build3SimpleClassArray(valueAnno, type);
        } else if (type.equals(List.class)) {
            // 此处必须通过Field获取可能的泛型类型，Class会丢失泛型信息！
            Class<?> elementType = Class.forName(((ParameterizedType) field.getGenericType())
                    .getActualTypeArguments()[0].getTypeName());
            return build3SimpleClassList(valueAnno, elementType);
        } else if (type.equals(Set.class)) {
            Class<?> elementType = Class.forName(((ParameterizedType) field.getGenericType())
                    .getActualTypeArguments()[0].getTypeName());
            return build3SimpleClassSet(valueAnno, elementType);
        } else if (type.equals(Map.class)) {
            Class<?> keyType = Class.forName(((ParameterizedType) field.getGenericType())
                    .getActualTypeArguments()[0].getTypeName());
            Class<?> valueType = Class.forName(((ParameterizedType) field.getGenericType())
                    .getActualTypeArguments()[1].getTypeName());
            return build3SimpleClassMap(valueAnno, keyType, valueType);
        } else {
            return null;
        }
    }

    private enum SimpleParser {
        BOOLEAN_PARSER {
            @Override
            boolean isAccepted(String s) {
                return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
            }

            @Override
            Object parse(String s) {
                return Boolean.parseBoolean(s);
            }

        }, INTEGER_PARSER {
            @Override
            boolean isAccepted(String s) {
                try {
                    Integer.parseInt(s);
                    return true;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }

            @Override
            Object parse(String s) {
                return Integer.parseInt(s);
            }
        }, STRING_PARSER {
            @Override
            boolean isAccepted(String s) {
                return true;
            }

            @Override
            Object parse(String s) {
                return s;
            }
        }, NULL_PARSER {
            @Override
            boolean isAccepted(String s) {
                return false;
            }

            @Override
            Object parse(String s) {
                return null;
            }
        };

        abstract boolean isAccepted(String s);

        abstract Object parse(String s);

        public static boolean isAccepted(Class<?> clazz, String s) {
            return getInstance(clazz).isAccepted(s);
        }

        static final Map<Class<?>, SimpleParser> PARSER_MAP = new HashMap<Class<?>, SimpleParser>() {{
            put(boolean.class, BOOLEAN_PARSER);
            put(Boolean.class, BOOLEAN_PARSER);
            put(int.class, INTEGER_PARSER);
            put(Integer.class, INTEGER_PARSER);
            put(String.class, STRING_PARSER);
        }};

        private static SimpleParser getInstance(Class<?> clazz) {
            return PARSER_MAP.getOrDefault(clazz, NULL_PARSER);
        }
    }
}