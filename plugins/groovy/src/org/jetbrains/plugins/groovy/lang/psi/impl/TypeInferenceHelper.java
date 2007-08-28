package org.jetbrains.plugins.groovy.lang.psi.impl;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ConcurrentWeakHashMap;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrCodeBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.controlFlow.Instruction;
import org.jetbrains.plugins.groovy.lang.psi.controlFlow.impl.ControlFlowBuilder;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.DFAEngine;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.types.TypeDfaInstance;
import org.jetbrains.plugins.groovy.lang.psi.dataFlow.types.TypesSemilattice;

import java.util.ArrayList;
import java.util.Map;

/**
 * @author ven
 */
public class TypeInferenceHelper {
  public TypeInferenceHelper(Project project) {
    ((PsiManagerEx) PsiManager.getInstance(project)).registerRunnableToRunOnAnyChange(new Runnable() {
      public void run() {
        myCalculatedTypeInferences.clear();
      }
    });
  }

  private final ConcurrentWeakHashMap<GroovyPsiElement, Map<GrReferenceExpression, PsiType>> myCalculatedTypeInferences =
      new ConcurrentWeakHashMap<GroovyPsiElement, Map<GrReferenceExpression, PsiType>>();

  @Nullable
  public PsiType getInferredType(GrReferenceExpression refExpr) {
    if (isInferenceInProgress()) {
      return getTypeBinding(refExpr);
    }
    
    GroovyPsiElement scope = PsiTreeUtil.getParentOfType(refExpr, GrMethod.class, GrClosableBlock.class, GroovyFileBase.class);
    if (scope instanceof GrMethod) {
      scope = ((GrMethod) scope).getBlock();
    }

    if (scope != null) {
      Map<GrReferenceExpression, PsiType> map = myCalculatedTypeInferences.get(scope);
      if (map == null) {
        map = inferTypes(scope);
        myCalculatedTypeInferences.put(scope, map);
      }
      return map.get(refExpr);
    }

    return null;
  }

  private ThreadLocal<Map<String, PsiType>> myCurrentEnvironment = new ThreadLocal<Map<String, PsiType>>();

  public void setCurrentEnvironment(Map<String, PsiType> env) {
    myCurrentEnvironment.set(env);
  }

  public boolean isInferenceInProgress() {
    return myCurrentEnvironment.get() != null;
  }

  public PsiType getTypeBinding(GrReferenceExpression refExpr) {
    if (refExpr.getQualifierExpression() == null) {
      final String refName = refExpr.getReferenceName();
      final Map<String, PsiType> env = myCurrentEnvironment.get();
      if (env != null) {
        return env.get(refName);
      }
    }

    return null;
  }

  private Map<GrReferenceExpression, PsiType> inferTypes(GroovyPsiElement scope) {
    final Instruction[] flow;
    if (scope instanceof GrCodeBlock) {
      flow = ((GrCodeBlock) scope).getControlFlow();
    } else {
      flow = new ControlFlowBuilder().buildControlFlow(scope, null, null); //no need to cache
    }
    final TypeDfaInstance dfaInstance = new TypeDfaInstance();
    final TypesSemilattice semilattice = new TypesSemilattice(scope.getManager());
    final DFAEngine<Map<String, PsiType>> engine = new DFAEngine<Map<String, PsiType>>(flow, dfaInstance, semilattice);

    final ArrayList<Map<String, PsiType>> infos = engine.performDFA();
    Map<GrReferenceExpression, PsiType> result = new HashMap<GrReferenceExpression, PsiType>();
    for (int i = 0; i < flow.length; i++) {
      Instruction instruction = flow[i];
      final PsiElement element = instruction.getElement();
      if (element instanceof GrReferenceExpression) {
        final GrReferenceExpression ref = (GrReferenceExpression) element;
        if (ref.getQualifierExpression() == null) {
          final String refName = ref.getReferenceName();
          if (refName != null) {
            final PsiType type = infos.get(i).get(refName);
            if (type != null) {
              result.put(ref, type);
            }
          }
        }
      }
    }

    return result;
  }

}
