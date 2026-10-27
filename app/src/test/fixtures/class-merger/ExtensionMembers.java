import DEX_PACKAGE.AccessFlags;
import DEX_PACKAGE.iface.ClassDef;
import DEX_PACKAGE.iface.Field;
import DEX_PACKAGE.iface.Method;
import DEX_PACKAGE.immutable.ImmutableClassDef;
import DEX_PACKAGE.immutable.ImmutableField;
import DEX_PACKAGE.immutable.ImmutableMethod;
import DEX_PACKAGE.immutable.ImmutableMethodParameter;
import DEX_PACKAGE.immutable.value.ImmutableIntEncodedValue;
import java.util.*;

public class ExtensionMembers {
    private static final int FLAGS = AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue();
    private static Field field(String type, String name, int value) {
        return new ImmutableField(type, name, "I", FLAGS,
            new ImmutableIntEncodedValue(value), Set.of(), Set.of());
    }
    private static Method method(String type, String name, String result, String... parameters) {
        var args = Arrays.stream(parameters)
            .map(p -> new ImmutableMethodParameter(p, Set.of(), null)).toList();
        return new ImmutableMethod(type, name, args, result,
            FLAGS | AccessFlags.NATIVE.getValue(), Set.of(), Set.of(), null);
    }
    private static ClassDef type(String descriptor, List<Field> fields, List<Method> methods) {
        return new ImmutableClassDef(descriptor, AccessFlags.PUBLIC.getValue(), "Ljava/lang/Object;",
            List.of(), null, Set.of(), fields, methods);
    }
    private static ClassDef merge(String operation, ClassDef existing, ClassDef incoming) throws Exception {
        var merger = Class.forName("PATCHER_PACKAGE.util.ClassMerger");
        var function = merger.getDeclaredMethod(operation, ClassDef.class, ClassDef.class);
        function.setAccessible(true);
        return (ClassDef) function.invoke(merger.getField("INSTANCE").get(null), existing, incoming);
    }
    private static List<Field> fields(ClassDef type) {
        var result = new ArrayList<Field>();
        type.getFields().forEach(result::add);
        return result;
    }
    private static List<Method> methods(ClassDef type) {
        var result = new ArrayList<Method>();
        type.getMethods().forEach(result::add);
        return result;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void verifyFields() throws Exception {
        var descriptor = "Lapp/morphe/extension/facebook/theme/AmoledTheme;";
        var existing = type(descriptor, List.of(field(descriptor, "existing", 7)), List.of());
        var incoming = type(descriptor, List.of(
            field(descriptor, "existing", 9), field(descriptor, "PAGE", 42)), List.of());
        var merged = merge("addMissingFields", existing, incoming);
        var members = fields(merged);
        check(members.stream().anyMatch(f -> f.getName().equals("PAGE") &&
            f.getType().equals("I") && AccessFlags.STATIC.isSet(f.getAccessFlags())),
            "Missing incoming static PAGE:I");
        check(members.size() == 2, "Duplicated existing fields");
        var kept = members.stream().filter(f -> f.getName().equals("existing")).findFirst().orElseThrow();
        check(((DEX_PACKAGE.iface.value.IntEncodedValue) kept.getInitialValue()).getValue() == 7,
            "Overwrote an existing field value");
        check(fields(merge("addMissingFields", merged, incoming)).size() == 2, "Field merge is not idempotent");
        check(fields(merge("addMissingFields", existing, type(descriptor, List.of(), List.of()))).size() == 1,
            "An empty extension duplicated existing fields");
    }
    private static void verifyMethods() throws Exception {
        var descriptor = "Lapp/morphe/extension/youtube/patches/HideStatusBarPatch;";
        var existing = type(descriptor, List.of(), List.of(method(descriptor, "existing", "V")));
        var incoming = type(descriptor, List.of(), List.of(
            method(descriptor, "existing", "V"),
            method(descriptor, "isPatchIncluded", "Z"),
            method(descriptor, "isPatchIncluded", "Z", "I"),
            method(descriptor, "isPatchIncluded", "I")));
        var merged = merge("addMissingMethods", existing, incoming);
        var members = methods(merged);
        check(members.stream().anyMatch(m -> m.getName().equals("isPatchIncluded") &&
            m.getParameterTypes().isEmpty() && m.getReturnType().equals("Z") &&
            AccessFlags.STATIC.isSet(m.getAccessFlags())), "Missing incoming isPatchIncluded()Z");
        check(members.size() == 4, "Lost an overload or duplicated an existing method");
        check(methods(merge("addMissingMethods", merged, incoming)).size() == 4, "Method merge is not idempotent");
        check(methods(merge("addMissingMethods", existing, type(descriptor, List.of(), List.of()))).size() == 1,
            "An empty extension duplicated existing methods");
    }
    private static void verifyMorpheProto() throws Exception {
        for (var descriptor : List.of("Lcom/google/protobuf/MessageLite;",
                "Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass$StreamingData;")) {
            var existing = type(descriptor, List.of(), List.of(
                method(descriptor, "writeTo", "V", "Laxmu;"),
                method(descriptor, "writeTo", "V", "Ljava/io/OutputStream;")));
            var incoming = type(descriptor, List.of(field(descriptor, "extensionField", 1)), List.of(
                method(descriptor, "writeTo", "V", "Lcom/google/protobuf/CodedOutputStream;"),
                method(descriptor, "writeTo", "V", "Ljava/io/OutputStream;")));
            check(methods(merge("addMissingMethods", existing, incoming)).size() == 2,
                "Extension protobuf changed the app's fingerprint match count");
            check(fields(merge("addMissingFields", existing, incoming)).isEmpty(),
                "Extension protobuf fields changed the app's library");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("fields")) verifyFields();
        if (args.length == 0 || args[0].equals("methods")) verifyMethods();
        if (args.length == 0 && "PATCHER_PACKAGE".equals("app.morphe.patcher")) verifyMorpheProto();
        System.out.println("PASS: incoming fields/methods, existing members, overloads and repeated merges");
    }
}
