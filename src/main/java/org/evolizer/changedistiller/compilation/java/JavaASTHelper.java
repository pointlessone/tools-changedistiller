package org.evolizer.changedistiller.compilation.java;

import java.io.File;
import java.util.List;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.evolizer.changedistiller.compilation.ASTHelper;
import org.evolizer.changedistiller.distilling.java.Comment;
import org.evolizer.changedistiller.distilling.java.CommentCleaner;
import org.evolizer.changedistiller.distilling.java.CommentCollector;
import org.evolizer.changedistiller.distilling.java.JavaASTNodeTypeConverter;
import org.evolizer.changedistiller.distilling.java.JavaDeclarationConverter;
import org.evolizer.changedistiller.distilling.java.JavaMethodBodyConverter;
import org.evolizer.changedistiller.model.classifiers.EntityType;
import org.evolizer.changedistiller.model.classifiers.SourceRange;
import org.evolizer.changedistiller.model.entities.AttributeHistory;
import org.evolizer.changedistiller.model.entities.ClassHistory;
import org.evolizer.changedistiller.model.entities.MethodHistory;
import org.evolizer.changedistiller.model.entities.SourceCodeEntity;
import org.evolizer.changedistiller.model.entities.StructureEntityVersion;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureNode;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureNode.Type;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureTreeBuilder;
import org.evolizer.changedistiller.treedifferencing.Node;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * Implementation of {@link ASTHelper} for the Java programming language.
 * 
 * @author Beat Fluri
 */
public class JavaASTHelper implements ASTHelper<JavaStructureNode> {

    private JavaDeclarationConverter fDeclarationConverter;
    private JavaMethodBodyConverter fBodyConverter;
    private JavaASTNodeTypeConverter fASTHelper;
    private JavaCompilation fCompilation;
    private List<Comment> fComments;

    @Inject
    JavaASTHelper(
            @Assisted File file,
            JavaASTNodeTypeConverter astHelper,
            JavaDeclarationConverter declarationConverter,
            JavaMethodBodyConverter bodyConverter) {
        fCompilation = JavaCompilationUtils.compile(file);
        prepareComments();
        fASTHelper = astHelper;
        fDeclarationConverter = declarationConverter;
        fBodyConverter = bodyConverter;
    }

    private void prepareComments() {
        cleanComments(collectComments());
    }

    private void cleanComments(List<Comment> comments) {
        CommentCleaner visitor = new CommentCleaner(fCompilation.getSource());
        for (Comment comment : comments) {
            visitor.process(comment);
        }
        fComments = visitor.getComments();
    }

    private List<Comment> collectComments() {
        CommentCollector collector = new CommentCollector(fCompilation.getCompilationUnit(), fCompilation.getSource());
        collector.collect();
        return collector.getComments();
    }

    @Override
    public Node createDeclarationTree(JavaStructureNode node) {
        ASTNode astNode = node.getASTNode();
        Node root = createRootNode(node, astNode);
        return createDeclarationTree(astNode, root);
    }

    private Node createDeclarationTree(ASTNode astNode, Node root) {
        fDeclarationConverter.initialize(root, fCompilation.getScanner());
        if (astNode instanceof TypeDeclaration) {
            ((TypeDeclaration) astNode).traverse(fDeclarationConverter, (ClassScope) null);
        } else if (astNode instanceof AbstractMethodDeclaration) {
            ((AbstractMethodDeclaration) astNode).traverse(fDeclarationConverter, (ClassScope) null);
        } else if (astNode instanceof FieldDeclaration) {
            ((FieldDeclaration) astNode).traverse(fDeclarationConverter, null);
        }
        return root;
    }

    @Override
    public Node createDeclarationTree(JavaStructureNode node, String qualifiedName) {
        ASTNode astNode = node.getASTNode();
        Node root = createRootNode(node, astNode);
        root.setValue(qualifiedName);
        return createDeclarationTree(astNode, root);
    }

    private Node createRootNode(JavaStructureNode node, ASTNode astNode) {
        Node root = new Node(fASTHelper.convertNode(astNode), node.getFullyQualifiedName());
        root.setEntity(createSourceCodeEntity(node));
        return root;
    }

    @Override
    public Node createMethodBodyTree(JavaStructureNode node) {
        ASTNode astNode = node.getASTNode();
        if (astNode instanceof AbstractMethodDeclaration) {
            Node root = createRootNode(node, astNode);
            fBodyConverter.initialize(root, astNode, fComments, fCompilation.getScanner());
            ((AbstractMethodDeclaration) astNode).traverse(fBodyConverter, (ClassScope) null);
            return root;
        }
        return null;
    }

    @Override
    public JavaStructureNode createStructureTree() {
        CompilationUnitDeclaration cu = fCompilation.getCompilationUnit();
        JavaStructureNode node = new JavaStructureNode(Type.CU, null, null, cu);
        cu.traverse(new JavaStructureTreeBuilder(node), (CompilationUnitScope) null);
        return node;
    }

    @Override
    public EntityType convertType(JavaStructureNode node) {
        return fASTHelper.convertNode(node.getASTNode());
    }

    @Override
    public SourceCodeEntity createSourceCodeEntity(JavaStructureNode node) {
        return new SourceCodeEntity(
                node.getFullyQualifiedName(),
                convertType(node),
                createSourceRange(node.getASTNode()));
    }

    private SourceRange createSourceRange(ASTNode astNode) {
        if (astNode instanceof TypeDeclaration) {
            TypeDeclaration type = (TypeDeclaration) astNode;
            return new SourceRange(type.declarationSourceStart, type.declarationSourceEnd);
        }
        if (astNode instanceof AbstractMethodDeclaration) {
            AbstractMethodDeclaration method = (AbstractMethodDeclaration) astNode;
            return new SourceRange(method.declarationSourceStart, method.declarationSourceEnd);
        }
        if (astNode instanceof FieldDeclaration) {
            FieldDeclaration field = (FieldDeclaration) astNode;
            return new SourceRange(field.declarationSourceStart, field.declarationSourceEnd);
        }
        return new SourceRange(astNode.sourceStart(), astNode.sourceEnd());
    }

    @Override
    public StructureEntityVersion createStructureEntityVersion(JavaStructureNode node) {
        return new StructureEntityVersion(convertType(node), node.getFullyQualifiedName(), 0);
    }

    @Override
    public StructureEntityVersion createMethodInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        MethodHistory mh;
        StructureEntityVersion method = createStructureEntityVersion(node);
        if (classHistory.getMethodHistories().containsKey(method.getUniqueName())) {
            mh = classHistory.getMethodHistories().get(method.getUniqueName());
            mh.addVersion(method);
        } else {
            mh = new MethodHistory(method);
            classHistory.getMethodHistories().put(method.getUniqueName(), mh);
        }
        return method;
    }

    @Override
    public StructureEntityVersion createFieldInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        AttributeHistory ah = null;
        StructureEntityVersion attribute = createStructureEntityVersion(node);
        if (classHistory.getAttributeHistories().containsKey(attribute.getUniqueName())) {
            ah = classHistory.getAttributeHistories().get(attribute.getUniqueName());
            ah.addVersion(attribute);
        } else {
            ah = new AttributeHistory(attribute);
            classHistory.getAttributeHistories().put(attribute.getUniqueName(), ah);
        }
        return attribute;

    }

    @Override
    public StructureEntityVersion createInnerClassInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        ClassHistory ch = null;
        StructureEntityVersion clazz = createStructureEntityVersion(node);
        if (classHistory.getInnerClassHistories().containsKey(clazz.getUniqueName())) {
            ch = classHistory.getInnerClassHistories().get(clazz.getUniqueName());
            ch.addVersion(clazz);
        } else {
            ch = new ClassHistory(clazz);
            classHistory.getInnerClassHistories().put(clazz.getUniqueName(), ch);
        }
        return clazz;

    }

}