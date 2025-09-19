package com.loliwolf.gostructcopy.core;

import com.goide.psi.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.ResolveState;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a textual representation of a Go struct with nested structs flattened into standalone definitions.
 */
public final class GoStructCopyProcessor {
    private static final String NOT_STRUCT_ERROR = "The selected type is not a struct";
    private static final String NOT_FOUND_ERROR = "Could not locate a struct type. Place the caret inside a struct declaration.";
    private static final String INDENT = "\t";

    @NotNull
    public GoStructCopyResult expandAtCaret(@NotNull GoFile file, int caretOffset) {
        PsiElement element = file.findElementAt(caretOffset);
        if (element == null) {
            return GoStructCopyResult.failure(NOT_FOUND_ERROR);
        }

        GoTypeSpec spec = PsiTreeUtil.getParentOfType(element, GoTypeSpec.class, false);
        if (spec == null) {
            GoTypeReferenceExpression reference = PsiTreeUtil.getParentOfType(element, GoTypeReferenceExpression.class, false);
            if (reference != null) {
                PsiElement resolved = reference.resolve();
                if (resolved instanceof GoTypeSpec resolvedSpec) {
                    spec = resolvedSpec;
                }
            }
        }
        if (spec == null) {
            GoCompositeLit compositeLit = PsiTreeUtil.getParentOfType(element, GoCompositeLit.class, false);
            if (compositeLit != null) {
                GoTypeReferenceExpression reference = compositeLit.getTypeReferenceExpression();
                if (reference != null) {
                    PsiElement resolved = reference.resolve();
                    if (resolved instanceof GoTypeSpec resolvedSpec) {
                        spec = resolvedSpec;
                    }
                }
            }
        }

        if (spec == null) {
            return GoStructCopyResult.failure(NOT_FOUND_ERROR);
        }
        return expand(spec);
    }

    @NotNull
    public GoStructCopyResult expand(@NotNull GoTypeSpec typeSpec) {
        GoStructType structType = resolveStructType(typeSpec, new HashSet<>());
        String typeName = Optional.ofNullable(typeSpec.getName()).orElse("<anonymous>");
        
        DefinitionCollector collector = new DefinitionCollector();
        
        if (structType != null) {
            // Handle struct types
            collector.enqueue(typeName, structType, typeSpec);
        } else {
            // Handle type aliases - use enqueueSpec which already handles this case
            collector.enqueueSpec(typeSpec);
        }
        
        List<StructDefinition> definitions = collector.process();
        if (definitions.isEmpty()) {
            return GoStructCopyResult.failure(NOT_STRUCT_ERROR);
        }

        String content = renderDefinitions(definitions);
        String message = "Copied struct " + typeName + " to clipboard";
        return GoStructCopyResult.success(content, message);
    }

    @Nullable
    private GoStructType resolveStructType(@NotNull GoTypeSpec typeSpec, @NotNull Set<GoTypeSpec> visited) {
        if (!visited.add(typeSpec)) {
            return null;
        }
        GoType type = typeSpec.getSpecType().getType();
        GoStructType directStruct = findStructLiteral(type);
        if (directStruct != null) {
            return directStruct;
        }
        if (type != null) {
            GoType underlying = type.getUnderlyingType(ResolveState.initial());
            GoStructType underlyingStruct = findStructLiteral(underlying);
            if (underlyingStruct != null) {
                return underlyingStruct;
            }
            GoTypeReferenceExpression reference = type.getTypeReferenceExpression();
            if (reference != null) {
                PsiElement resolved = reference.resolve();
                if (resolved instanceof GoTypeSpec otherSpec) {
                    return resolveStructType(otherSpec, visited);
                }
            }
        }
        return null;
    }

    @Nullable
    private static GoStructType findStructLiteral(@Nullable GoType type) {
        if (type instanceof GoStructType structType) {
            return structType;
        }
        return null;
    }

    private String renderDefinitions(@NotNull List<StructDefinition> definitions) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < definitions.size(); i++) {
            StructDefinition definition = definitions.get(i);
            
            if (definition.isTypeAlias()) {
                // Render type alias
                builder.append("type ").append(definition.name()).append(" ").append(definition.underlyingType()).append("\n");
            } else {
                // Render struct
                builder.append("type ").append(definition.name()).append(" struct {\n");
                for (FieldDefinition field : definition.fields()) {
                    builder.append(INDENT);
                    if (field.isEmbedded()) {
                        builder.append(field.type());
                    } else {
                        builder.append(field.name()).append(' ').append(field.type());
                    }
                    if (field.tag() != null) {
                        builder.append(' ').append(field.tag());
                    }
                    builder.append('\n');
                }
                builder.append("}\n");
            }
            
            if (i < definitions.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private boolean shouldExpandSpec(@NotNull GoTypeSpec spec) {
        PsiFile file = spec.getContainingFile();
        if (file instanceof GoFile goFile) {
            String importPath = goFile.getImportPath(true);
            if (!StringUtil.isEmpty(importPath)) {
                if (!importPath.contains(".")) {
                    return false;
                }
            }
        }
        return true;
    }

    private @Nullable String sanitizeJsonTag(@Nullable GoTag tag) {
        if (tag == null) {
            return null;
        }
        String text = tag.getText();
        if (StringUtil.isEmpty(text)) {
            return null;
        }
        String raw = text;
        if (text.startsWith("`") && text.endsWith("`")) {
            raw = text.substring(1, text.length() - 1).trim();
        }
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("json=\"")) {
                return '`' + part + '`';
            }
            if (part.startsWith("json:\"")) {
                return '`' + part + '`';
            }
        }
        return null;
    }

    private final class DefinitionCollector {
        private final ArrayDeque<StructTarget> queue = new ArrayDeque<>();
        private final LinkedHashMap<String, StructDefinition> definitions = new LinkedHashMap<>();
        private final Set<String> queuedNames = new HashSet<>();
        private final Map<GoTypeSpec, String> specNameCache = new HashMap<>();
        private final Map<GoStructType, String> anonymousNames = new HashMap<>();
        private int anonymousCounter = 1;

        void enqueue(@NotNull String desiredName, @NotNull GoStructType structType, @Nullable GoTypeSpec spec) {
            NameReservation reservation = reserveUniqueName(desiredName, spec);
            if (!reservation.newlyReserved()) {
                return;
            }
            queue.addLast(new StructTarget(reservation.name(), structType, spec));
        }

        @Nullable
        String enqueueSpec(@NotNull GoTypeSpec spec) {
            if (!shouldExpandSpec(spec)) {
                return null;
            }
            String originalName = spec.getName();
            if (StringUtil.isEmpty(originalName)) {
                return null;
            }
            NameReservation reservation = reserveUniqueName(originalName, spec);
            if (StringUtil.isEmpty(reservation.name())) {
                return null;
            }
            if (!reservation.newlyReserved()) {
                return reservation.name();
            }

            GoStructType structType = resolveStructType(spec, new HashSet<>());
            if (structType != null) {
                queue.addLast(new StructTarget(reservation.name(), structType, spec));
                return reservation.name();
            }

            GoType specType = spec.getSpecType().getType();
            if (specType == null) {
                return reservation.name();
            }

            String underlyingTypeName = renderType(specType, reservation.name(), null);
            StructDefinition definition = StructDefinition.typeAlias(reservation.name(), underlyingTypeName);
            definitions.put(reservation.name(), definition);
            return reservation.name();
        }

        @NotNull
        String registerAnonymous(@NotNull GoStructType structType, @NotNull String ownerName, @Nullable String fieldName) {
            String existing = anonymousNames.get(structType);
            if (existing != null) {
                return existing;
            }
            String baseName;
            if (!StringUtil.isEmpty(fieldName)) {
                baseName = StringUtil.capitalize(fieldName);
            } else {
                baseName = StringUtil.capitalize(ownerName) + "Anonymous";
            }
            if (StringUtil.isEmpty(baseName)) {
                baseName = "Anonymous";
            }
            NameReservation reservation = reserveUniqueName(baseName, null);
            String candidate = reservation.name();
            if (StringUtil.isEmpty(candidate)) {
                candidate = baseName + anonymousCounter++;
            }
            anonymousNames.put(structType, candidate);
            queue.addLast(new StructTarget(candidate, structType, null));
            return candidate;
        }

        @NotNull
        List<StructDefinition> process() {
            while (!queue.isEmpty()) {
                StructTarget target = queue.removeFirst();
                if (definitions.containsKey(target.typeName())) {
                    continue;
                }
                List<FieldDefinition> fields = buildFields(target);
                definitions.put(target.typeName(), new StructDefinition(target.typeName(), fields));
            }
            
            // Separate struct definitions and type aliases, ensuring structs come first
            List<StructDefinition> result = new ArrayList<>();
            List<StructDefinition> typeAliases = new ArrayList<>();
            
            for (StructDefinition definition : definitions.values()) {
                if (definition.isTypeAlias()) {
                    typeAliases.add(definition);
                } else {
                    result.add(definition);
                }
            }
            
            // Add type aliases after struct definitions
            result.addAll(typeAliases);
            return result;
        }

        @NotNull
        private List<FieldDefinition> buildFields(@NotNull StructTarget target) {
            List<FieldDefinition> result = new ArrayList<>();
            List<GoFieldDeclaration> declarations = target.structType().getFieldDeclarationList();
            for (GoFieldDeclaration declaration : declarations) {
                GoAnonymousFieldDefinition anonymousField = declaration.getAnonymousFieldDefinition();
                if (anonymousField != null) {
                    String typeText = renderType(anonymousField.getType(), target.typeName(), anonymousField.getIdentifier() != null ? anonymousField.getIdentifier().getText() : null);
                    if (!typeText.isEmpty()) {
                        String tag = sanitizeJsonTag(declaration.getTag());
                        result.add(FieldDefinition.embedded(typeText, tag));
                    }
                    continue;
                }

                GoType fieldType = declaration.getType();
                String tag = sanitizeJsonTag(declaration.getTag());
                List<GoFieldDefinition> fieldDefinitions = declaration.getFieldDefinitionList();
                if (fieldDefinitions.isEmpty()) {
                    String typeText = renderType(fieldType, target.typeName(), null);
                    if (!typeText.isEmpty()) {
                        result.add(FieldDefinition.embedded(typeText, tag));
                    }
                    continue;
                }
                for (GoFieldDefinition fieldDefinition : fieldDefinitions) {
                    PsiElement identifier = fieldDefinition.getIdentifier();
                    String name = identifier != null ? identifier.getText() : null;
                    if (StringUtil.isEmpty(name)) {
                        continue;
                    }
                    String typeText = renderType(fieldType, target.typeName(), name);
                    if (typeText.isEmpty()) {
                        continue;
                    }
                    result.add(FieldDefinition.named(name, typeText, tag));
                }
            }
            return result;
        }

        @NotNull
        private String renderType(@Nullable GoType type, @NotNull String ownerName, @Nullable String fieldName) {
            if (type == null) {
                return "";
            }
            if (type instanceof GoStructType structType) {
                return registerAnonymous(structType, ownerName, fieldName);
            }
            if (type instanceof GoPointerType pointerType) {
                String inner = renderType(pointerType.getType(), ownerName, fieldName);
                return inner.isEmpty() ? "" : "*" + inner;
            }
            if (type instanceof GoArrayOrSliceType arrayType) {
                String length = Optional.ofNullable(arrayType.getExpression()).map(PsiElement::getText).orElse("");
                if (arrayType.getTripleDot() != null) {
                    length = "...";
                }
                String prefix = length.isEmpty() ? "[]" : "[" + length + "]";
                String inner = renderType(arrayType.getType(), ownerName, fieldName);
                return prefix + inner;
            }
            if (type instanceof GoMapType mapType) {
                String key = renderType(Objects.requireNonNull(mapType.getKeyType()), ownerName, fieldName);
                String value = renderType(Objects.requireNonNull(mapType.getValueType()), ownerName, fieldName);
                if (key.isEmpty()) {
                    key = "interface{}";
                }
                if (value.isEmpty()) {
                    value = "interface{}";
                }
                return "map[" + key + "]" + value;
            }

            GoTypeReferenceExpression reference = type.getTypeReferenceExpression();
            if (reference != null) {
                PsiElement resolved = reference.resolve();
                if (resolved instanceof GoTypeSpec spec) {
                    String assignedName = enqueueSpec(spec);
                    if (!StringUtil.isEmpty(assignedName)) {
                        String typeText = type.getText();
                        String specName = spec.getName();
                        if (StringUtil.isEmpty(typeText) || StringUtil.isEmpty(specName)) {
                            return assignedName;
                        }
                        if (typeText.equals(specName) || typeText.endsWith("." + specName)) {
                            return assignedName;
                        }
                        String replaced = typeText.replace(specName, assignedName);
                        return StringUtil.isEmpty(replaced) ? assignedName : replaced;
                    }
                }
                return type.getText();
            }

            return type.getText();
        }

        @NotNull
        private NameReservation reserveUniqueName(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            if (spec != null) {
                String cached = specNameCache.get(spec);
                if (!StringUtil.isEmpty(cached)) {
                    return new NameReservation(cached, false);
                }
            }

            List<String> candidates = buildNameCandidates(desiredName, spec);
            for (String candidate : candidates) {
                if (StringUtil.isEmpty(candidate)) {
                    continue;
                }
                if (queuedNames.add(candidate)) {
                    if (spec != null) {
                        specNameCache.put(spec, candidate);
                    }
                    return new NameReservation(candidate, true);
                }
            }

            String base = candidates.isEmpty() ? "Type" : candidates.get(candidates.size() - 1);
            if (StringUtil.isEmpty(base)) {
                base = "Type";
            }
            int counter = 2;
            while (true) {
                String candidate = base + counter;
                if (queuedNames.add(candidate)) {
                    if (spec != null) {
                        specNameCache.put(spec, candidate);
                    }
                    return new NameReservation(candidate, true);
                }
                counter++;
            }
        }

        @NotNull
        private List<String> buildNameCandidates(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            List<String> result = new ArrayList<>();
            if (!StringUtil.isEmpty(desiredName)) {
                result.add(desiredName);
            }
            if (spec == null) {
                return result;
            }
            PsiFile file = spec.getContainingFile();
            if (file instanceof GoFile goFile) {
                String packageName = goFile.getPackageName();
                if (!StringUtil.isEmpty(packageName)) {
                    String candidate = StringUtil.capitalize(packageName) + desiredName;
                    if (!StringUtil.isEmpty(candidate)) {
                        result.add(candidate);
                    }
                }
                String importPath = goFile.getImportPath(true);
                if (!StringUtil.isEmpty(importPath)) {
                    String lastSegment = extractLastSegment(importPath);
                    if (!StringUtil.isEmpty(lastSegment)) {
                        String candidate = StringUtil.capitalize(lastSegment) + desiredName;
                        if (!StringUtil.isEmpty(candidate)) {
                            result.add(candidate);
                        }
                    }
                }
            }
            return result;
        }

        @Nullable
        private String extractLastSegment(@NotNull String importPath) {
            int index = importPath.lastIndexOf('/');
            if (index >= 0 && index < importPath.length() - 1) {
                return importPath.substring(index + 1);
            }
            return importPath;
        }

        private record NameReservation(@Nullable String name, boolean newlyReserved) {
        }
    }

    private record StructTarget(String typeName, GoStructType structType, @Nullable GoTypeSpec spec) {
    }

    private record StructDefinition(String name, List<FieldDefinition> fields, boolean isTypeAlias, @Nullable String underlyingType) {
        // Constructor for struct definitions
        public StructDefinition(String name, List<FieldDefinition> fields) {
            this(name, fields, false, null);
        }
        
        // Constructor for type alias definitions
        public static StructDefinition typeAlias(String name, String underlyingType) {
            return new StructDefinition(name, List.of(), true, underlyingType);
        }
    }

    private record FieldDefinition(@Nullable String name, @NotNull String type, @Nullable String tag) {
        static FieldDefinition named(@NotNull String name, @NotNull String type, @Nullable String tag) {
            return new FieldDefinition(name, type, tag);
        }

        static FieldDefinition embedded(@NotNull String type, @Nullable String tag) {
            return new FieldDefinition(null, type, tag);
        }

        boolean isEmbedded() {
            return name == null;
        }
    }

    public record GoStructCopyResult(boolean success, @Nullable String content, @NotNull String message) {
        public static GoStructCopyResult success(@NotNull String content, @NotNull String message) {
            return new GoStructCopyResult(true, content, message);
        }

        public static GoStructCopyResult failure(@NotNull String message) {
            return new GoStructCopyResult(false, null, message);
        }
    }
}

