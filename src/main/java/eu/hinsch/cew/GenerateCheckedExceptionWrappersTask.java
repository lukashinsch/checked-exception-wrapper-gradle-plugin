package eu.hinsch.cew;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.MultiTypeParameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclaratorId;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * Created by lh on 14/03/15.
 */
public class GenerateCheckedExceptionWrappersTask extends DefaultTask {

    private final CheckedExceptionWrapperGeneratorPluginExtension extension;

    public GenerateCheckedExceptionWrappersTask() {
        extension = (CheckedExceptionWrapperGeneratorPluginExtension)getProject()
                .getExtensions().getByName("checkedExceptionWrapperGenerator");
    }

    @TaskAction
    void generate() throws ParseException {

        getLogger().debug("classes: " + extension.getClasses());
        getLogger().debug("output folder: " + extension.getOutputFolder());

        String suffix = extension.getGeneratedClassNameSuffix();

        extension.getClasses().forEach(className -> {
            InputStream inputStream = getSource(className);

            CompilationUnit cu = null;
            try {
                cu = JavaParser.parse(inputStream);
            } catch (ParseException e) {
                e.printStackTrace();
            }
            enhanceSource(cu);
            saveSource(className, cu);

            getLogger().info("Created ${className}${suffix}.java");
        });
    }

    private InputStream getSource(String className) {
        return getProject().getConfigurations().getByName("checkedExceptionWrapperGenerator")
                .resolve()
                .stream()
                .peek(System.out::println)
                .map(jar -> {
                    ZipFile zip = null;
                    try {
                        zip = new ZipFile(jar);
                        ZipEntry entry = zip.getEntry(className + ".java");
                        if (entry != null) {
                            return zip.getInputStream(entry);
                        }
                        return null;
                    } catch (IOException e) {
                        throw new GradleException("", e);
                    }
                })
                .filter(stream -> stream != null)
                .findFirst()
                .orElseThrow(() -> new GradleException("cannot find source for " + className));
    }

    void enhanceSource(CompilationUnit cu) {
        String suffix = extension.getGeneratedClassNameSuffix();
        List<TypeDeclaration> types = cu.getTypes();
        for (TypeDeclaration type : types) {

            type.setName(type.getName() + suffix);

            List<BodyDeclaration> members = type.getMembers();

            for (BodyDeclaration member : members) {
                if (member instanceof MethodDeclaration) {
                    MethodDeclaration method = (MethodDeclaration) member;
                    List<NameExpr> t = method.getThrows();
                    if (t != null && t.size() > 0) {
                        method.setThrows(null);

                        BlockStmt body = method.getBody();

                        List<Statement> originalStatements = body.getStmts();
                        body.setStmts(new ArrayList<>());

                        BlockStmt tryBlock = new BlockStmt(originalStatements);
                        CatchClause catchClause = new CatchClause(createCatchExceptionParameter(), createCatchBlock());

                        // TODO avoid empty finally block
                        TryStmt tryStmt = new TryStmt(tryBlock, singletonList(catchClause), new BlockStmt());
                        tryStmt.setResources(emptyList());

                        body.getStmts().add(tryStmt);
                    }
                }
            }
        }
    }

    BlockStmt createCatchBlock() {
        // TODO add context info to message (parameter values)
        Expression errorMessage = new StringLiteralExpr(extension.getExceptionMessage());
        Expression exceptionParameter = new NameExpr("e");
        ObjectCreationExpr newRuntimeException = new ObjectCreationExpr(null,
                new ClassOrInterfaceType(
                        extension.getRuntimeExceptionClass()),
                asList(errorMessage, exceptionParameter));

        ThrowStmt throwStmt = new ThrowStmt();
        throwStmt.setExpr(newRuntimeException);

        BlockStmt catchBlock = new BlockStmt();
        catchBlock.setStmts(singletonList(throwStmt));
        return catchBlock;
    }

    MultiTypeParameter createCatchExceptionParameter() {
        return new MultiTypeParameter(0, emptyList(),
            singletonList(new ClassOrInterfaceType("Exception")),
            new VariableDeclaratorId("e"));
    }

    void saveSource(String className, CompilationUnit cu) {
        String suffix = extension.getGeneratedClassNameSuffix();
        Paths.get(extension.getOutputFolder(), className).getParent().toFile().mkdirs();
        try {
            FileUtils.writeStringToFile(new File(extension.getOutputFolder() + "/" + className + suffix + ".java"),
                    cu.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}