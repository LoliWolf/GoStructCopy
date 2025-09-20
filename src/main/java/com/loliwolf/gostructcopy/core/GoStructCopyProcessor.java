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
                builder.append("type ").append(definition.name()).append(" ").append(definition.underlyingType()).append('\n');
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
                
                // Add extra line after struct (but not after type alias)
                if (i < definitions.size() - 1) {
                    builder.append('\n');
                }
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
        private final Map<String, String> specNameCache = new HashMap<>();
        private final Map<GoStructType, String> anonymousNames = new HashMap<>();
        private final Map<String, List<GoTypeSpec>> nameToSpecs = new HashMap<>();
        private final Set<GoTypeSpec> processedSpecs = new HashSet<>();
        private int anonymousCounter = 1;
        private boolean isRebuilding = false;

        void enqueue(@NotNull String desiredName, @NotNull GoStructType structType, @Nullable GoTypeSpec spec) {
            System.out.println("DEBUG enqueue: Attempting to enqueue " + desiredName + " (spec: " + (spec != null ? spec.getName() + " from " + getPackagePath(spec) : "null") + ")");
            
            // Check if this exact spec is already processed
            if (spec != null && processedSpecs.contains(spec)) {
                System.out.println("DEBUG enqueue: Skipping " + desiredName + " - spec already processed");
                return;
            }
            
            // Check if this struct is already in the queue (by spec only, regardless of name)
            for (StructTarget target : queue) {
                if (target.spec() == spec) {
                    System.out.println("DEBUG enqueue: Skipping " + desiredName + " - spec already in queue as " + target.typeName());
                    return;
                }
            }
            
            // Check if there's already a resolved name in the cache
            String finalName = desiredName;
            if (spec != null) {
                String cacheKey = generateCacheKey(desiredName, spec);
                if (specNameCache.containsKey(cacheKey)) {
                    finalName = specNameCache.get(cacheKey);
                    System.out.println("DEBUG enqueue: Using cached resolved name " + finalName + " for " + desiredName);
                } else {
                    NameReservation reservation = reserveUniqueName(desiredName, spec);
                    finalName = reservation.name();
                }
            } else {
                NameReservation reservation = reserveUniqueName(desiredName, spec);
                finalName = reservation.name();
            }
            
            StructTarget target = new StructTarget(finalName, structType, spec);
            queue.addLast(target);
            System.out.println("DEBUG enqueue: Successfully enqueued " + desiredName + " as " + finalName);
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
            
            // 收集同名的类型规格
            nameToSpecs.computeIfAbsent(originalName, k -> new ArrayList<>()).add(spec);
            
            // 暂时使用原始名称，稍后在process方法中处理冲突
            String cacheKey = generateCacheKey(originalName, spec);
            if (specNameCache.containsKey(cacheKey)) {
                String cachedName = specNameCache.get(cacheKey);
                return cachedName;
            }
            
            specNameCache.put(cacheKey, originalName);
            
            GoStructType structType = resolveStructType(spec, new HashSet<>());
            if (structType != null) {
                enqueue(originalName, structType, spec);
                return originalName;
            }

            GoType specType = spec.getSpecType().getType();
            if (specType == null) {
                return originalName;
            }
            String underlyingTypeName = renderType(specType, originalName, null);
            StructDefinition definition = StructDefinition.typeAlias(originalName, underlyingTypeName);
            definitions.put(originalName, definition);
            return originalName;
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
            // First pass: build all fields to collect type references
            Map<String, StructTarget> structTargets = new HashMap<>();
            
            while (!queue.isEmpty()) {
                StructTarget target = queue.removeFirst();
                // Check if this spec has already been processed, not just the type name
                if (target.spec() != null && processedSpecs.contains(target.spec())) {
                    System.out.println("DEBUG process: Skipping already processed spec " + target.spec().getName() + " from " + getPackagePath(target.spec()));
                    continue;
                }
                List<FieldDefinition> fields = buildFields(target);
                // Use a unique key for each struct to avoid overwrites
                String uniqueKey = target.spec() != null ? 
                    generateCacheKey(target.typeName(), target.spec()) : target.typeName();
                definitions.put(uniqueKey, new StructDefinition(target.typeName(), fields));
                structTargets.put(uniqueKey, target);
                
                // Mark this spec as processed
                if (target.spec() != null) {
                    processedSpecs.add(target.spec());
                    System.out.println("DEBUG process: Marked spec " + target.spec().getName() + " from " + getPackagePath(target.spec()) + " as processed");
                }
            }
            
            // Second pass: resolve name conflicts after all types are collected
            resolveNameConflicts();
            
            // Third pass: rebuild everything with resolved names
            isRebuilding = true;
            LinkedHashMap<String, StructDefinition> newDefinitions = new LinkedHashMap<>();
            
            // First, rebuild struct definitions with updated field types
            for (Map.Entry<String, StructDefinition> entry : definitions.entrySet()) {
                String uniqueKey = entry.getKey();
                StructDefinition definition = entry.getValue();
                if (!definition.isTypeAlias()) {
                    StructTarget target = structTargets.get(uniqueKey);
                    if (target != null) {
                        // Get the resolved name for this struct using the original spec name
                        String originalSpecName = target.spec() != null ? target.spec().getName() : definition.name();
                        String cacheKey = generateCacheKey(originalSpecName, target.spec());
                        String resolvedName = specNameCache.getOrDefault(cacheKey, definition.name());
                        

                        
                        // Rebuild fields with updated type names
                        List<FieldDefinition> updatedFields = buildFields(target);
                        newDefinitions.put(resolvedName, new StructDefinition(resolvedName, updatedFields));
                    }
                }
            }
            
            // Then, rebuild type alias definitions with resolved names and updated underlying types
            for (Map.Entry<String, List<GoTypeSpec>> entry : nameToSpecs.entrySet()) {
                String originalName = entry.getKey();
                List<GoTypeSpec> specs = entry.getValue();
                
                for (GoTypeSpec spec : specs) {
                    // Check if this is a type alias (not a struct)
                    GoType specType = spec.getSpecType().getType();
                    if (specType != null && resolveStructType(spec, new HashSet<>()) == null) {
                        // This is a type alias
                        String cacheKey = generateCacheKey(originalName, spec);
                        String resolvedName = specNameCache.getOrDefault(cacheKey, originalName);
                        
                        // Render the underlying type with updated names
                        String updatedUnderlyingType = renderType(specType, resolvedName, null);
                        
                        StructDefinition typeAliasDefinition = StructDefinition.typeAlias(resolvedName, updatedUnderlyingType);
                        newDefinitions.put(resolvedName, typeAliasDefinition);
                    }
                }
            }
            
            // Replace the old definitions with the new ones
            definitions.clear();
            definitions.putAll(newDefinitions);
            
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
        
        private void resolveNameConflicts() {
            // Group specs by desired name
            for (Map.Entry<String, List<GoTypeSpec>> entry : nameToSpecs.entrySet()) {
                String desiredName = entry.getKey();
                List<GoTypeSpec> specs = entry.getValue();
                
                if (specs.size() > 1) {
                    // Multiple specs with same name - need to resolve conflicts
                    Set<String> packagePaths = new HashSet<>();
                    for (GoTypeSpec spec : specs) {
                        String packagePath = getPackagePath(spec);
                        packagePaths.add(packagePath);
                    }
                    
                    if (packagePaths.size() > 1) {
                        // Real conflict - different packages with same type name
                        System.out.println("DEBUG: Resolving conflict for " + desiredName);
                        
                        // Check if any of the specs are type aliases
                        boolean hasTypeAlias = false;
                        for (GoTypeSpec spec : specs) {
                            GoType specType = spec.getSpecType().getType();
                            if (specType != null && !(specType instanceof GoStructType)) {
                                hasTypeAlias = true;
                                break;
                            }
                        }
                        
                        // Clear related cache entries to force regeneration
                        for (GoTypeSpec spec : specs) {
                            String cacheKey = generateCacheKey(desiredName, spec);
                            specNameCache.remove(cacheKey);
                        }
                        
                        // Remove original name from queuedNames to allow reassignment
                        queuedNames.remove(desiredName);
                        
                        // Generate new names for each conflicting type based on package
                        boolean isFirst = true;
                        for (GoTypeSpec spec : specs) {
                            String packagePath = getPackagePath(spec);
                            String newName;
                            
                            // For type aliases, all get package prefix
                            // For structs, first keeps original name, others get package prefix
                            if (hasTypeAlias || !isFirst) {
                                newName = generatePackageBasedName(desiredName, packagePath);
                            } else {
                                newName = desiredName;
                                isFirst = false;
                            }
                            
                            // Ensure the new name is unique
                            String finalName = newName;
                            int suffix = 2;
                            while (queuedNames.contains(finalName)) {
                                finalName = newName + suffix++;
                            }
                            
                            queuedNames.add(finalName);
                            String cacheKey = generateCacheKey(desiredName, spec);
                            specNameCache.put(cacheKey, finalName);
                            
                            // Update queue items with new name
                            updateQueueWithNewName(desiredName, finalName, spec);
                            
                            if (!hasTypeAlias) {
                                isFirst = false;
                            }
                        }
                    }
                }
            }
            
            System.out.println("DEBUG: Final cache after conflict resolution: " + specNameCache);
        }
        
        private String generatePackageBasedName(String typeName, String packagePath) {
            // Extract package name from path (e.g., "example.com/pkg1" -> "pkg1")
            String packageName = packagePath;
            if (packagePath.contains("/")) {
                packageName = packagePath.substring(packagePath.lastIndexOf("/") + 1);
            }
            
            // Capitalize first letter of package name and append to type name
            String capitalizedPackage = packageName.substring(0, 1).toUpperCase() + packageName.substring(1);
            return capitalizedPackage + typeName;
        }
        
        private void updateQueueWithNewName(String oldName, String newName, GoTypeSpec spec) {
            // Find and update queue items that match this spec
            List<StructTarget> queueList = new ArrayList<>(queue);
            for (int i = 0; i < queueList.size(); i++) {
                StructTarget target = queueList.get(i);
                if (target.spec() == spec && target.typeName().equals(oldName)) {
                    // Replace the target with updated name at the same position
                    queue.clear();
                    queueList.set(i, new StructTarget(newName, target.structType(), target.spec()));
                    queue.addAll(queueList);
                    System.out.println("DEBUG updateQueueWithNewName: Updated " + oldName + " to " + newName + " at position " + i);
                    break;
                }
            }
        }

        @NotNull
        private List<FieldDefinition> buildFields(@NotNull StructTarget target) {
            System.out.println("DEBUG buildFields: Processing struct " + target.typeName());
            List<FieldDefinition> result = new ArrayList<>();
            List<GoFieldDeclaration> declarations = target.structType().getFieldDeclarationList();
            if (declarations == null || declarations.isEmpty()) {
                System.out.println("DEBUG buildFields: No field declarations for " + target.typeName());
                return result;
            }
            
            for (GoFieldDeclaration declaration : declarations) {
                System.out.println("DEBUG buildFields: Processing field declaration in " + target.typeName());
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
                    // Check if this spec should be expanded
                    if (!shouldExpandSpec(spec)) {
                        // For types that shouldn't be expanded (like Go SDK types), return original text
                        return type.getText();
                    }
                    
                    String assignedName;
                    if (isRebuilding) {
                        // During rebuilding, directly look up the resolved name from cache
                        String cacheKey = generateCacheKey(spec.getName(), spec);
                        assignedName = specNameCache.getOrDefault(cacheKey, spec.getName());
                    } else {
                        assignedName = enqueueSpec(spec);
                    }
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
        private String getPackagePath(@NotNull GoTypeSpec spec) {
            PsiFile file = spec.getContainingFile();
            if (file instanceof GoFile goFile) {
                String importPath = goFile.getImportPath(true);
                if (!StringUtil.isEmpty(importPath)) {
                    return importPath;
                }
            }
            return "";
        }

        private String generateCacheKey(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            if (spec == null) {
                // 对于匿名结构体，使用特殊的缓存键
                return "anonymous:" + desiredName;
            }
            String specPath = getPackagePath(spec);
            return specPath + ":" + desiredName + ":" + spec.hashCode();
        }

        @NotNull
        private NameReservation reserveUniqueName(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            String cacheKey = generateCacheKey(desiredName, spec);
            String cachedName = specNameCache.get(cacheKey);
            if (cachedName != null) {
                return new NameReservation(cachedName, false);
            }

            // 检查是否存在同名但不同包路径的类型
            boolean hasConflict = hasNameConflict(desiredName, spec);
            
            if (!hasConflict && !queuedNames.contains(desiredName)) {
                // 没有冲突且名称未被占用，直接使用原始名称
                queuedNames.add(desiredName);
                specNameCache.put(cacheKey, desiredName);
                return new NameReservation(desiredName, true);
            } else {
                // 存在冲突或名称已被占用，生成候选名称
                List<String> candidates = buildNameCandidates(desiredName, spec);
                for (String candidate : candidates) {
                    if (!queuedNames.contains(candidate)) {
                        queuedNames.add(candidate);
                        specNameCache.put(cacheKey, candidate);
                        return new NameReservation(candidate, true);
                    }
                }
                
                // 如果所有候选名称都被占用，使用数字后缀
                int suffix = 2;
                String fallback;
                do {
                    fallback = desiredName + suffix++;
                } while (queuedNames.contains(fallback));
                
                queuedNames.add(fallback);
                specNameCache.put(cacheKey, fallback);
                return new NameReservation(fallback, true);
            }
        }

        private boolean hasNameConflict(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            if (spec == null) {
                return false;
            }
            
            String currentPackagePath = getPackagePath(spec);
            
            // 检查已缓存的名称中是否有同名但不同包路径的类型
            for (Map.Entry<String, String> entry : specNameCache.entrySet()) {
                String[] keyParts = entry.getKey().split(":");
                if (keyParts.length >= 3 && keyParts[1].equals(desiredName)) {
                    String existingPackagePath = keyParts[0];
                    if (!existingPackagePath.equals(currentPackagePath)) {
                        return true; // 发现同名但不同包路径的类型
                    }
                }
            }
            
            return false;
        }

        @NotNull
        private List<String> buildNameCandidates(@NotNull String desiredName, @Nullable GoTypeSpec spec) {
            List<String> result = new ArrayList<>();
            
            if (spec == null) {
                // 对于匿名结构体，总是添加原始名称
                if (!StringUtil.isEmpty(desiredName)) {
                    result.add(desiredName);
                }

                return result;
            }
            
            // 总是添加原始名称作为第一候选
            if (!StringUtil.isEmpty(desiredName)) {
                result.add(desiredName);
            }
            PsiFile file = spec.getContainingFile();
            if (file instanceof GoFile goFile) {
                // 优先使用包名作为前缀
                String packageName = goFile.getPackageName();
                if (!StringUtil.isEmpty(packageName) && !packageName.equals("main")) {
                    String candidate = StringUtil.capitalize(packageName) + desiredName;
                    if (!StringUtil.isEmpty(candidate) && !candidate.equals(desiredName)) {
                        result.add(candidate);
                    }
                }
                
                // 如果包名不可用或为main，使用导入路径的最后一段
                String importPath = goFile.getImportPath(true);
                if (!StringUtil.isEmpty(importPath)) {
                    String lastSegment = extractLastSegment(importPath);
                    if (!StringUtil.isEmpty(lastSegment) && !lastSegment.equals(packageName)) {
                        String candidate = StringUtil.capitalize(lastSegment) + desiredName;
                        if (!StringUtil.isEmpty(candidate) && !candidate.equals(desiredName)) {
                            result.add(candidate);
                        }
                    }
                }
                
                // 如果导入路径有多层，尝试使用倒数第二段
                if (!StringUtil.isEmpty(importPath) && importPath.contains("/")) {
                    String[] segments = importPath.split("/");
                    if (segments.length >= 2) {
                        String secondLastSegment = segments[segments.length - 2];
                        if (!StringUtil.isEmpty(secondLastSegment) && 
                            !secondLastSegment.equals(packageName) && 
                            !secondLastSegment.equals(extractLastSegment(importPath))) {
                            String candidate = StringUtil.capitalize(secondLastSegment) + desiredName;
                            if (!StringUtil.isEmpty(candidate) && !candidate.equals(desiredName)) {
                                result.add(candidate);
                            }
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

