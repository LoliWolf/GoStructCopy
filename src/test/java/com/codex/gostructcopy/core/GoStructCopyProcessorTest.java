package com.loliwolf.gostructcopy.core;

import com.goide.psi.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class GoStructCopyProcessorTest {

    private final GoStructCopyProcessor processor = new GoStructCopyProcessor();

    @Test
    public void expandStruct_collectsNestedStructs() {
        GoFile file = createGoFile("main", null, null);

        GoTypeSpec addressSpec = createStructSpec("Address", file);
        GoStructType addressStruct = createStructType("Street", "string");
        GoSpecType addressSpecType = addressSpec.getSpecType();
        doReturn(addressStruct).when(addressSpecType).getType();

        GoTypeSpec userSpec = createParentSpec("User", file, addressSpec, "Address", addressSpec.getName());

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(userSpec);
        assertTrue(result.success());

        String expected = """
                type User struct {
                \tName string
                \tAddress Address
                }

                type Address struct {
                \tStreet string
                }
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_handlesRecursivePointer() {
        GoFile file = createGoFile("main", null, null);

        GoTypeSpec nodeSpec = createStructSpec("Node", file);
        GoStructType structType = createStructType("Next", "*Node");
        GoSpecType nodeSpecType = nodeSpec.getSpecType();
        doReturn(structType).when(nodeSpecType).getType();

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(nodeSpec);
        assertTrue(result.success());

        String expected = """
                type Node struct {
                \tNext *Node
                }
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_stopsAtGoSdk() {
        GoFile mainFile = createGoFile("main", null, null);
        GoFile timeFile = createGoFile("time", null, "time");
        GoTypeSpec timeSpec = createStructSpec("Time", timeFile);
        GoSpecType timeSpecType = timeSpec.getSpecType();
        doReturn(createStructType("wall", "uint64")).when(timeSpecType).getType();

        GoTypeSpec objectSpec = createParentSpec("Object", mainFile, timeSpec, "Modified", "time.Time");

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(objectSpec);
        assertTrue(result.success());

        String expected = """
                type Object struct {
                \tName string
                \tModified time.Time
                }
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_expandsTypeAliases() {
        GoFile file = createGoFile("main", null, null);

        // 创建 NormalizedName 类型别名
        GoTypeSpec normalizedNameSpec = createTypeAliasSpec("NormalizedName", "string", file);
        
        // 创建 Flag 结构体，包含 NormalizedName 类型的字段
        GoTypeSpec flagSpec = createStructSpec("Flag", file);
        GoStructType flagStruct = createStructTypeWithReference("Name", "string", "NormalizedName", normalizedNameSpec, "NormalizedName");
        GoSpecType flagSpecType = flagSpec.getSpecType();
        doReturn(flagStruct).when(flagSpecType).getType();

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(flagSpec);
        assertTrue(result.success());

        String expected = """
                type Flag struct {
                \tName string
                \tNormalizedName NormalizedName
                }

                type NormalizedName string
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_expandsNestedTypeAliases() {
        GoFile file = createGoFile("main", null, null);

        // 创建 NormalizedName 类型别名
        GoTypeSpec normalizedNameSpec = createTypeAliasSpec("NormalizedName", "string", file);
        
        // 创建 FlagSet 结构体，包含 map[NormalizedName]*Flag
        GoTypeSpec flagSetSpec = createStructSpec("FlagSet", file);
        GoStructType flagSetStruct = createStructTypeWithReference("Usage", "func()", "actual", normalizedNameSpec, "map[NormalizedName]*Flag");
        GoSpecType flagSetSpecType = flagSetSpec.getSpecType();
        doReturn(flagSetStruct).when(flagSetSpecType).getType();

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(flagSetSpec);
        assertTrue(result.success());

        String expected = """
                type FlagSet struct {
                \tUsage func()
                \tactual map[NormalizedName]*Flag
                }

                type NormalizedName string
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_expandsStructsAcrossImportedPackages() {
        GoFile outerFile = createGoFile("outer", null, null);
        GoFile innerFile = createGoFile("innerpkg", null, "github.com/example/innerpkg");
        GoFile deepFile = createGoFile("deeppkg", null, "github.com/example/deeppkg");

        GoTypeSpec deepSpec = createStructSpec("Deep", deepFile);
        GoStructType deepStruct = createStructType("Value", "string");
        GoSpecType deepSpecType = deepSpec.getSpecType();
        doReturn(deepStruct).when(deepSpecType).getType();

        GoTypeSpec innerSpec = createStructSpec("Inner", innerFile);
        GoStructType innerStruct = createStructTypeWithReference("ID", "int", "Deep", deepSpec, "deeppkg.Deep");
        GoSpecType innerSpecType = innerSpec.getSpecType();
        doReturn(innerStruct).when(innerSpecType).getType();

        GoTypeSpec outerSpec = createParentSpec("Outer", outerFile, innerSpec, "Inner", "innerpkg.Inner");

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(outerSpec);
        assertTrue(result.success());

        String expected = """
                type Outer struct {
                \tName string
                \tInner Inner
                }

                type Inner struct {
                \tID int
                \tDeep Deep
                }

                type Deep struct {
                \tValue string
                }
                """;
        assertEquals(expected, result.content());
    }

    @Test
    public void expandStruct_expandsSameNamedStructsFromDifferentPackages() {
        GoFile outerFile = createGoFile("outer", null, null);
        GoFile innerFile = createGoFile("innerpkg", null, "github.com/example/innerpkg");
        GoFile deepFile = createGoFile("deeppkg", null, "github.com/example/deeppkg");

        GoTypeSpec deepSpec = createStructSpec("Config", deepFile);
        GoStructType deepStruct = createStructType("Value", "string");
        GoSpecType deepSpecType = deepSpec.getSpecType();
        doReturn(deepStruct).when(deepSpecType).getType();

        GoTypeSpec innerSpec = createStructSpec("Config", innerFile);
        GoStructType innerStruct = createStructTypeWithReference("Enabled", "bool", "Config", deepSpec, "deeppkg.Config");
        GoSpecType innerSpecType = innerSpec.getSpecType();
        doReturn(innerStruct).when(innerSpecType).getType();

        GoTypeSpec outerSpec = createParentSpec("Outer", outerFile, innerSpec, "Config", "innerpkg.Config");

        GoStructCopyProcessor.GoStructCopyResult result = processor.expand(outerSpec);
        assertTrue(result.success());

        String expected = """
                type Outer struct {
                \tName string
                \tConfig Config
                }

                type Config struct {
                \tEnabled bool
                \tConfig DeeppkgConfig
                }

                type DeeppkgConfig struct {
                \tValue string
                }
                """;
        assertEquals(expected, result.content());
    }

    private GoTypeSpec createParentSpec(@NotNull String name, @NotNull GoFile file, @NotNull GoTypeSpec childSpec, @NotNull String childFieldName, @NotNull String childTypeText) {
        GoTypeSpec parentSpec = createStructSpec(name, file);
        GoStructType structType = createStructTypeWithReference("Name", "string", childFieldName, childSpec, childTypeText);
        GoSpecType parentSpecType = parentSpec.getSpecType();
        doReturn(structType).when(parentSpecType).getType();
        return parentSpec;
    }

    private GoStructType createStructType(@NotNull String fieldName, @NotNull String typeText) {
        GoStructType structType = mock(GoStructType.class);
        GoFieldDeclaration declaration = createFieldDeclaration(fieldName, typeText);
        when(structType.getFieldDeclarationList()).thenReturn(Collections.singletonList(declaration));
        return structType;
    }

    private GoStructType createStructTypeWithReference(@NotNull String firstField, @NotNull String firstType, @NotNull String secondField, @NotNull GoTypeSpec spec, @NotNull String secondTypeText) {
        GoStructType structType = mock(GoStructType.class);
        GoFieldDeclaration firstDeclaration = createFieldDeclaration(firstField, firstType);
        GoFieldDeclaration secondDeclaration = createReferenceField(secondField, spec, secondTypeText);
        when(structType.getFieldDeclarationList()).thenReturn(java.util.List.of(firstDeclaration, secondDeclaration));
        return structType;
    }

    private GoFieldDeclaration createReferenceField(@NotNull String name, @NotNull GoTypeSpec spec, @NotNull String typeText) {
        GoFieldDeclaration declaration = mock(GoFieldDeclaration.class);
        GoFieldDefinition definition = mock(GoFieldDefinition.class);
        GoType type = mock(GoType.class);
        GoTypeReferenceExpression reference = mock(GoTypeReferenceExpression.class);

        PsiElement identifier = mock(PsiElement.class);
        when(identifier.getText()).thenReturn(name);
        when(definition.getIdentifier()).thenReturn(identifier);
        when(declaration.getFieldDefinitionList()).thenReturn(Collections.singletonList(definition));
        when(type.getTypeReferenceExpression()).thenReturn(reference);
        when(reference.resolve()).thenReturn(spec);
        when(type.getText()).thenReturn(typeText);
        when(declaration.getType()).thenReturn(type);
        when(declaration.getTag()).thenReturn(null);
        return declaration;
    }

    private GoFieldDeclaration createFieldDeclaration(@NotNull String name, @NotNull String typeText) {
        GoFieldDeclaration declaration = mock(GoFieldDeclaration.class);
        GoFieldDefinition definition = mock(GoFieldDefinition.class);
        GoType type = mock(GoType.class);
        PsiElement identifier = mock(PsiElement.class);

        when(identifier.getText()).thenReturn(name);
        when(definition.getIdentifier()).thenReturn(identifier);
        when(type.getText()).thenReturn(typeText);
        when(type.getTypeReferenceExpression()).thenReturn(null);
        when(declaration.getFieldDefinitionList()).thenReturn(Collections.singletonList(definition));
        when(declaration.getType()).thenReturn(type);
        when(declaration.getTag()).thenReturn(null);
        return declaration;
    }

    private GoTypeSpec createStructSpec(@NotNull String name, @NotNull GoFile file) {
        GoTypeSpec spec = createEmptySpec(name, file);
        return spec;
    }

    private GoTypeSpec createTypeAliasSpec(@NotNull String name, @NotNull String underlyingType, @NotNull GoFile file) {
        GoTypeSpec spec = mock(GoTypeSpec.class, RETURNS_DEEP_STUBS);
        GoType aliasType = mock(GoType.class);
        
        // 模拟类型别名：type NormalizedName string
        when(aliasType.getText()).thenReturn(underlyingType);
        when(aliasType.getTypeReferenceExpression()).thenReturn(null);
        
        GoSpecType specType = spec.getSpecType();
        doReturn(aliasType).when(specType).getType();
        when(spec.getName()).thenReturn(name);
        when(spec.getContainingFile()).thenReturn(file);
        when(spec.isEquivalentTo(any())).thenAnswer(invocation -> invocation.getArgument(0) == spec);
        return spec;
    }

    private GoTypeSpec createEmptySpec(@NotNull String name, @NotNull GoFile file) {
        GoTypeSpec spec = mock(GoTypeSpec.class, RETURNS_DEEP_STUBS);
        GoStructType emptyStruct = mock(GoStructType.class);

        when(emptyStruct.getFieldDeclarationList()).thenReturn(Collections.emptyList());
        GoSpecType specType = spec.getSpecType();
        doReturn(emptyStruct).when(specType).getType();
        when(spec.getName()).thenReturn(name);
        when(spec.getContainingFile()).thenReturn(file);
        when(spec.isEquivalentTo(any())).thenAnswer(invocation -> invocation.getArgument(0) == spec);
        return spec;
    }

    @NotNull
    private GoFile createGoFile(@NotNull String packageName, @Nullable VirtualFile virtualFile, @Nullable String importPath) {
        GoFile file = mock(GoFile.class);
        when(file.getImportPath(true)).thenReturn(importPath);
        when(file.getPackageName()).thenReturn(packageName);
        when(file.getVirtualFile()).thenReturn(virtualFile);
        return file;
    }
}



