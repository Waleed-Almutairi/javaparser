package me.tomassetti.symbolsolver;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import jdk.nashorn.internal.ir.Symbol;
import me.tomassetti.symbolsolver.model.*;
import me.tomassetti.symbolsolver.model.declarations.MethodDeclaration;
import me.tomassetti.symbolsolver.model.declarations.ValueDeclaration;
import me.tomassetti.symbolsolver.model.declarations.TypeDeclaration;
import me.tomassetti.symbolsolver.model.usages.MethodUsage;
import me.tomassetti.symbolsolver.model.usages.TypeUsageOfTypeDeclaration;
import me.tomassetti.symbolsolver.model.javaparser.JavaParserFactory;
import me.tomassetti.symbolsolver.model.javaparser.UnsolvedSymbolException;
import me.tomassetti.symbolsolver.model.javaparser.contexts.MethodCallExprContext;
import me.tomassetti.symbolsolver.model.javaparser.declarations.JavaParserSymbolDeclaration;
import me.tomassetti.symbolsolver.model.usages.TypeUsage;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Class to be used by final users to solve symbols for JavaParser ASTs.
 */
public class JavaParserFacade {

    private TypeSolver typeSolver;
    private SymbolSolver symbolSolver;

    private static Logger logger = Logger.getLogger(JavaParserFacade.class.getCanonicalName());
    static {
        logger.setLevel(Level.FINEST);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.FINEST);
        logger.addHandler(consoleHandler);
    }

    public JavaParserFacade(TypeSolver typeSolver) {
        this.typeSolver = typeSolver;
        this.symbolSolver = new SymbolSolver(typeSolver);
    }

    public SymbolReference solve(NameExpr nameExpr) {
        return symbolSolver.solveSymbol(nameExpr.getName(), nameExpr);
    }

    public SymbolReference solve(Expression expr) {
        if (expr instanceof NameExpr) {
            return solve((NameExpr)expr);
        } else {
            throw new IllegalArgumentException(expr.getClass().getCanonicalName());
        }
    }

    /**
     * Given a method call find out to which method declaration it corresponds.
     */
    public SymbolReference<MethodDeclaration> solve(MethodCallExpr methodCallExpr) {
        List<TypeUsage> params = new LinkedList<>();
        List<LambdaTypeUsagePlaceholder> placeholders = new LinkedList<>();
        int i = 0;
        for (Expression expression : methodCallExpr.getArgs()) {
            if (expression instanceof LambdaExpr) {
                LambdaTypeUsagePlaceholder placeholder = new LambdaTypeUsagePlaceholder(i);
                params.add(placeholder);
                placeholders.add(placeholder);
            } else {
                params.add(new JavaParserFacade(typeSolver).getType(expression));
            }
            i++;
        }
        SymbolReference<MethodDeclaration> res = JavaParserFactory.getContext(methodCallExpr).solveMethod(methodCallExpr.getName(), params, typeSolver);
        for (LambdaTypeUsagePlaceholder placeholder : placeholders) {
            placeholder.setMethod(res);
        }
        return res;
    }

    public TypeUsage getType(Node node) {
        return getType(node, true);
    }

    /**
     * Should return more like a TypeApplication: a TypeDeclaration and possible parameters or array modifiers.
     * @return
     */
    public TypeUsage getType(Node node, boolean solveLambdas) {
        if (node == null) throw new IllegalArgumentException();
        if (node instanceof NameExpr) {
            NameExpr nameExpr = (NameExpr) node;
            logger.finest("getType on name expr " + node);
            return new SymbolSolver(typeSolver).solveSymbolAsValue(nameExpr.getName(), nameExpr).get().getUsage();
        } else if (node instanceof MethodCallExpr) {
            logger.finest("getType on method call " + node);
            // first solve the method
            MethodUsage ref = new JavaParserFacade(typeSolver).solveMethodAsUsage((MethodCallExpr) node);
            logger.finest("getType on method call " + node + " resolved to " + ref);
            logger.finest("getType on method call " + node + " return type is " + ref.returnType());
            return ref.returnType();
            // the type is the return type of the method
        } else if (node instanceof LambdaExpr) {
            if (node.getParentNode() instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) node.getParentNode();
                int pos = JavaParserSymbolDeclaration.getParamPos(node);
                SymbolReference<MethodDeclaration> refMethod = new JavaParserFacade(typeSolver).solve(callExpr);
                if (!refMethod.isSolved()) {
                    throw new UnsolvedSymbolException(null, callExpr.getName());
                }
                logger.finest("getType on lambda expr " + refMethod.getCorrespondingDeclaration().getName());
                //logger.finest("Method param " + refMethod.getCorrespondingDeclaration().getParam(pos));
                if (solveLambdas) {
                    return refMethod.getCorrespondingDeclaration().getParam(pos).getType(typeSolver).getUsage(node);
                } else {
                    return new TypeUsageOfTypeDeclaration(refMethod.getCorrespondingDeclaration().getParam(pos).getType(typeSolver));
                }
                //System.out.println("LAMBDA " + node.getParentNode());
                //System.out.println("LAMBDA CLASS " + node.getParentNode().getClass().getCanonicalName());
                //TypeUsage typeOfMethod = new JavaParserFacade(typeSolver).getType(node.getParentNode());
                //throw new UnsupportedOperationException("The type of a lambda expr depends on the position and its return value");
            } else {
                throw new UnsupportedOperationException("The type of a lambda expr depends on the position and its return value");
            }
        } else if (node instanceof VariableDeclarator) {
            if (node.getParentNode() instanceof FieldDeclaration) {
                FieldDeclaration parent = (FieldDeclaration) node.getParentNode();
                return new JavaParserFacade(typeSolver).convertToUsage(parent.getType(), parent);
            } else if (node.getParentNode() instanceof VariableDeclarationExpr) {
                VariableDeclarationExpr parent = (VariableDeclarationExpr) node.getParentNode();
                return new JavaParserFacade(typeSolver).convertToUsage(parent.getType(), parent);
            } else {
                throw new UnsupportedOperationException(node.getParentNode().getClass().getCanonicalName());
            }
        } else if (node instanceof Parameter) {
            Parameter parameter = (Parameter)node;
            if (parameter.getType() instanceof UnknownType){
                throw new IllegalStateException("Parameter has unknown type: " + parameter);
            }
            return new JavaParserFacade(typeSolver).convertToUsage(parameter.getType(), parameter);
        } else if (node instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) node;
            Optional<Value> value = new SymbolSolver(typeSolver).solveSymbolAsValue(fieldAccessExpr.getField(), fieldAccessExpr);
            if (value.isPresent()) {
                return value.get().getUsage();
            } else {
                throw new UnsolvedSymbolException(null, fieldAccessExpr.getField());
            }
        } else if (node instanceof ObjectCreationExpr) {
            ObjectCreationExpr objectCreationExpr = (ObjectCreationExpr)node;
            TypeUsage typeUsage = new JavaParserFacade(typeSolver).convertToUsage(objectCreationExpr.getType(), node);
            return typeUsage;
        } else {
            throw new UnsupportedOperationException(node.getClass().getCanonicalName());
        }
    }

    public TypeUsage convertToUsage(Type type, Node context) {
        if (type instanceof UnknownType){
            throw new IllegalArgumentException("Unknown type");
        }
        return convertToUsage(type, JavaParserFactory.getContext(context));
    }

    public TypeUsage convertToUsage(Type type, Context context) {
        if (type instanceof ReferenceType) {
            ReferenceType referenceType = (ReferenceType) type;
            // TODO consider array modifiers
            return convertToUsage(referenceType.getType(), context);
        } else if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType)type;
            SymbolReference<TypeDeclaration> ref = context.solveType(classOrInterfaceType.getName(), typeSolver);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(null, classOrInterfaceType.getName());
            }
            TypeDeclaration typeDeclaration = ref.getCorrespondingDeclaration();
            List<TypeUsage> typeParameters = Collections.emptyList();
            if (classOrInterfaceType.getTypeArgs() != null) {
                typeParameters = classOrInterfaceType.getTypeArgs().stream().map((pt)->convertToUsage(pt, context)).collect(Collectors.toList());
            }
            return new TypeUsageOfTypeDeclaration(typeDeclaration, typeParameters);
        } else {
            throw new UnsupportedOperationException(type.getClass().getCanonicalName());
        }
    }

    private SymbolReference<MethodDeclaration> solveMethod(MethodCallExpr methodCallExpr) {
        List<TypeUsage> params = new ArrayList<>();
        if (methodCallExpr.getArgs() != null) {
            for (Expression param : methodCallExpr.getArgs()) {
                params.add(getType(param));
            }
        }
        return new MethodCallExprContext(methodCallExpr).solveMethod(methodCallExpr.getName(), params, typeSolver);
    }

    public TypeDeclaration convert(Type type, Node node) {
        return convert(type, JavaParserFactory.getContext(node));
    }

    public TypeDeclaration convert(Type type, Context context) {
        if (type instanceof ReferenceType) {
            ReferenceType referenceType = (ReferenceType) type;
            // TODO consider array modifiers
            return convert(referenceType.getType(), context);
        } else if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType)type;
            SymbolReference<TypeDeclaration> ref = context.solveType(classOrInterfaceType.getName(), typeSolver);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(null, classOrInterfaceType.getName());
            }
            return ref.getCorrespondingDeclaration();
        } else {
            throw new UnsupportedOperationException(type.getClass().getCanonicalName());
        }
    }

    public MethodUsage solveMethodAsUsage(MethodCallExpr call) {
        List<TypeUsage> params = new ArrayList<>();
        if (call.getArgs() != null) {
            for (Expression param : call.getArgs()) {
                params.add(getType(param, false));
            }
        }
        TypeUsage typeOfScope = getType(call.getScope());
        logger.finest("facade solveMethodAsUsage, params " + params);
        logger.finest("facade solveMethodAsUsage, scope " + typeOfScope);

        // TODO take params from scope and substitute them in ref

        Optional<MethodUsage> ref = new MethodCallExprContext(call).solveMethodAsUsage(call.getName(), params, typeSolver);

        if (!ref.isPresent()){
            throw new UnsolvedSymbolException(null, call.getName());
        } else {
            logger.finest("facade solveMethodAsUsage, ref " + ref.get());
            MethodUsage methodUsage = ref.get();
            methodUsage = replaceParams(methodUsage, typeOfScope);
            TypeUsage returnType = replaceParams(methodUsage.returnType(), typeOfScope);
            methodUsage = methodUsage.replaceReturnType(returnType);
            return methodUsage;
        }
    }

    private MethodUsage replaceParams(MethodUsage methodUsage, TypeUsage typeOfScope) {
        logger.finest("ReplaceParams " + methodUsage);
        logger.finest("ReplaceParams N params " + methodUsage.getParamTypes().size());
        for (int i=0;i<methodUsage.getParamTypes().size();i++) {
            TypeUsage typeUsage = methodUsage.getParamTypes().get(i);
            TypeUsage replaced = replaceParams(typeUsage, typeOfScope);
            logger.finest("ReplaceParams param type " + typeUsage);
            if (replaced != typeUsage) {
                logger.finest("ReplaceParams param -> " + replaced);
                methodUsage = methodUsage.replaceParamType(i, replaced);
            }

        }
        logger.finest("Final method usage "+methodUsage);
        return methodUsage;
    }

    private TypeUsage replaceParams(TypeUsage typeToReplace, TypeUsage typeOfScope) {
        if (typeToReplace.isTypeVariable()) {
            Optional<TypeUsage> replacement = typeOfScope.parameterByName(typeToReplace.getTypeName());
            if (replacement.isPresent()) {
                return replacement.get();
            } else {
                return typeToReplace;
            }
        } else {
            for (int i=0;i<typeToReplace.parameters().size();i++){
                TypeUsage typeUsage = typeToReplace.parameters().get(i);
                TypeUsage replaced = replaceParams(typeUsage, typeOfScope);
                if (replaced != typeUsage) {
                    typeToReplace = typeToReplace.replaceParam(i, replaced);
                }
            }
            return typeToReplace;
        }
    }

    public MethodUsage convertToUsage(MethodDeclaration methodDeclaration, Context context) {
        return new MethodUsage(methodDeclaration, typeSolver);
    }
}
