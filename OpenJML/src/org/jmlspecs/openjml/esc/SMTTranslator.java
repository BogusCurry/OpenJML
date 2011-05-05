package org.jmlspecs.openjml.esc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.jmlspecs.openjml.JmlPretty;
import org.jmlspecs.openjml.JmlToken;
import org.jmlspecs.openjml.JmlTree.JmlStatementExpr;
import org.jmlspecs.openjml.JmlTreeScanner;
import org.smtlib.ICommand;
import org.smtlib.IExpr;
import org.smtlib.IExpr.IDeclaration;
import org.smtlib.ISort;
import org.smtlib.command.C_assert;
import org.smtlib.command.C_check_sat;
import org.smtlib.command.C_declare_fun;
import org.smtlib.command.C_declare_sort;
import org.smtlib.command.C_define_fun;
import org.smtlib.command.C_set_logic;
import org.smtlib.command.C_set_option;
import org.smtlib.impl.Command;
import org.smtlib.impl.Factory;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.JCArrayAccess;
import com.sun.tools.javac.tree.JCTree.JCArrayTypeTree;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCAssignOp;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCInstanceOf;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewArray;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCTypeDisjunction;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCUnary;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.JCWildcard;
import com.sun.tools.javac.tree.JCTree.TypeBoundKind;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

public class SMTTranslator extends JmlTreeScanner {

    /** The error log */
    protected Log log;
    
    /** The symbol table for this compilation context */
    protected Symtab syms;
    
    protected Names names;
    
    /** The factory for creating SMTLIB expressions */
    protected Factory F;
    
    /** SMTLIB subexpressions - the result of each visit call */
    protected IExpr result;
    
    protected ISort refSort;
    protected IExpr.ISymbol nullRef;
    protected IExpr.ISymbol thisRef;
    
    
    /** The SMTLIB script as it is being constructed */
    protected Command.Script script; // FIXME - make abstract
    
    public SMTTranslator(Context context) {
        log = Log.instance(context);
        syms = Symtab.instance(context);
        names = Names.instance(context);
    }
    
    public ICommand.IScript convert(BasicProgram program) {
        F = new org.smtlib.impl.Factory();
        script = new Command.Script();
        ICommand c;
        
        // set the logic
        c = new C_set_option(F.keyword(":produce-models",null),F.symbol("true",null));
        script.add(c);
        c = new C_set_logic(F.symbol("AUFLIRA",null));
        script.add(c);
        
        // add background statements
        c = new C_declare_sort(F.symbol("REF",null),F.numeral(0));
        script.add(c);
        c = new C_declare_fun(nullRef = F.symbol("NULL",null),new LinkedList<ISort>(), refSort = F.createSortExpression(F.symbol("REF",null)));
        script.add(c);
        c = new C_declare_fun(thisRef = F.symbol("this",null),new LinkedList<ISort>(), refSort);
        script.add(c);
        c = new C_assert(F.fcn(F.symbol("distinct",null), thisRef, nullRef));
        script.add(c);
        
        for (JCExpression e: program.background()) {
            try {
                e.accept(this);
                IExpr ex = result;
                c = new C_assert(ex);
                script.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued
            }
        }
        
        // add declarations
        
        for (JCIdent id: program.declarations) {
            try {
                c = new C_declare_fun(F.symbol(id.toString(), null),
                        new LinkedList<ISort>(),
                        convertSort(id.type));
                script.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued
            }
        }
        
        // add definitions
        for (BasicProgram.Definition e: program.definitions()) {
            try {
                e.value.accept(this);
                c = new C_define_fun(F.symbol(e.id.toString(), null),
                        new LinkedList<IDeclaration>(),
                        convertSort(e.id.type),
                        result);
                script.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued
            }
        }
        
        // Because blocks have forward references to later blocks, but
        // backward references to variables in earlier blocks, we declare
        // all the block variables first
        for (BasicProgram.BasicBlock b: program.blocks()) {
            ICommand cc = new C_declare_fun(F.symbol(b.id.toString(),null), new LinkedList<ISort>(), F.Bool());
            script.add(cc);
        }
        
        // add blocks
        for (BasicProgram.BasicBlock b: program.blocks()) {
            convertBasicBlock(b);
        }
        
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(F.symbol(program.startId().name.toString(),null));
        IExpr negStartID = F.fcn(F.symbol("not",null), args, null);
        ICommand cc = new C_assert(negStartID);
        script.add(cc);
        
        cc = new C_check_sat();
        script.add(cc);
        
        return script;
    }
    
    public void convertBasicBlock(BasicProgram.BasicBlock block) {
        Iterator<JCStatement> iter = block.statements.iterator();
        IExpr tail; 
        if (block.succeeding.isEmpty()) {
            tail = F.symbol("true",null);
        } else if (block.succeeding.size() == 1) {
            tail = F.symbol(block.succeeding.get(0).id.name.toString(),null);
        } else {
            ArrayList<IExpr> args = new ArrayList<IExpr>();
            for (BasicProgram.BasicBlock bb: block.succeeding) {
                args.add(F.symbol(bb.id.name.toString(),null));
            }
            tail = F.fcn(F.symbol("and",null),args,null);
        }
        IExpr ex = convert(iter,tail);
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(F.symbol(block.id.toString(),null));
        args.add(ex);
        ex = F.fcn(F.symbol("=",null),args,null);
        script.add(new C_assert(ex));
    }
    
    public IExpr convert(Iterator<JCStatement> iter, IExpr tail) {
        if (!iter.hasNext()) {
            return tail;
        }
        JCStatement stat = iter.next();
        try {
            if (stat instanceof JCVariableDecl) {
                JCVariableDecl decl = (JCVariableDecl)stat;
                // convert to a declaration or definition
                IExpr init = decl.init == null ? null : convertExpr(decl.init);
                
                ICommand c = init == null ?
                        new C_declare_fun(
                                F.symbol(decl.name.toString(), null),
                                new LinkedList<ISort>(),
                                convertSort(decl.type))
                : new C_define_fun(
                        F.symbol(decl.name.toString(), null),
                        new LinkedList<IDeclaration>(),
                        convertSort(decl.type),
                        init);
                 script.add(c);
                 return convert(iter,tail);
            } else if (stat instanceof JmlStatementExpr) {
                IExpr ex = convert(iter,tail);
                JmlStatementExpr s = (JmlStatementExpr)stat;
                if (s.token == JmlToken.ASSUME) {
                    IExpr exx = convertExpr(s.expression);
                    LinkedList<IExpr> args = new LinkedList<IExpr>();
                    args.add(exx);
                    args.add(ex);
                    return F.fcn(F.symbol("=>",null), args, null);
                } else if (s.token == JmlToken.ASSERT) {
                    IExpr exx = convertExpr(s.expression);
                    LinkedList<IExpr> args = new LinkedList<IExpr>();
                    args.add(exx);
                    args.add(ex);
                    return F.fcn(F.symbol("and",null), args, null);
                } else if (s.token == JmlToken.COMMENT) {
                    // skip - add script comment ? TODO
                    return ex;
                } else {
                    log.error("jml.internal", "Incorrect kind of token encountered when converting a BasicProgram to SMTLIB: " + s.token);
                    return ex;
                }
            } else {
                log.error("jml.internal", "Incorrect kind of statement encountered when converting a BasicProgram to SMTLIB: " + stat.getClass());
            }
        } catch (RuntimeException ee) {
            // skip - error already issued
        }
        return F.symbol("false",null);
        
    }
    
    public ISort convertSort(Type t) {
        if ( t == null) {
            log.error("jml.internal", "No type translation implemented when converting a BasicProgram to SMTLIB: " + t);
            throw new RuntimeException();
        } else if (t.equals(syms.booleanType)) {
            return F.Bool();
        } else if (t.tsym == syms.intType.tsym) { 
            return F.createSortExpression(F.symbol("Int", null));
        } else if (t.tag == syms.objectType.tag) {
            return refSort;
        } else {
            log.error("jml.internal", "No type translation implemented when converting a BasicProgram to SMTLIB: " + t);
            throw new RuntimeException();
        }
    }
    
    public IExpr convertExpr(JCExpression expr) {
        expr.accept(this);
        return result;
    }
    
    // We need to be able to translate expressions
    
    public void notImpl(JCTree tree) {
        log.error("jml.internal","Not yet supported expression node in converting BasicPrograms to SMTLIB: " + tree.getClass());
    }
    
    public void visitApply(JCMethodInvocation tree) {
        notImpl(tree);
        super.visitApply(tree);
    }

    public void visitNewClass(JCNewClass tree) {
        notImpl(tree);
        super.visitNewClass(tree);
    }

    public void visitNewArray(JCNewArray tree) {
        notImpl(tree);
        super.visitNewArray(tree);
    }

    public void visitAssign(JCAssign tree) {
        notImpl(tree);
        super.visitAssign(tree);
    }

    public void visitAssignop(JCAssignOp tree) {
        notImpl(tree);
        super.visitAssignop(tree);
    }

    public void visitUnary(JCUnary tree) {
        int op = tree.getTag();
        tree.arg.accept(this);
        IExpr arg = result;
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(arg);
        switch (op) {
            case JCTree.NOT:
                result = F.fcn(F.symbol("not",null), args, null);
                break;
            case JCTree.NEG:
                result = F.fcn(F.symbol("-",null), args, null);
                break;
            default:
                log.error("jml.internal","Don't know how to translate expression to SMTLIB: " + JmlPretty.write(tree));
                throw new RuntimeException();
        }
    }

    public void visitBinary(JCBinary tree) {
        int op = tree.getTag();
        tree.lhs.accept(this);
        IExpr lhs = result;
        tree.rhs.accept(this);
        IExpr rhs = result;
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(lhs);
        args.add(rhs);
        switch (op) {
            case JCTree.EQ:
                result = F.fcn(F.symbol("=",null), args, null);
                break;
            case JCTree.NE:
                result = F.fcn(F.symbol("distinct",null), args, null);
                break;
            case JCTree.AND:
                result = F.fcn(F.symbol("and",null), args, null);
                break;
            case JCTree.OR:
                result = F.fcn(F.symbol("or",null), args, null);
                break;
            case JCTree.LT:
                result = F.fcn(F.symbol("<",null), args, null);
                break;
            case JCTree.LE:
                result = F.fcn(F.symbol("<=",null), args, null);
                break;
            case JCTree.GT:
                result = F.fcn(F.symbol(">",null), args, null);
                break;
            case JCTree.GE:
                result = F.fcn(F.symbol(">=",null), args, null);
                break;
            case JCTree.PLUS:
                result = F.fcn(F.symbol("+",null), args, null);
                break;
            case JCTree.MINUS:
                result = F.fcn(F.symbol("-",null), args, null);
                break;
            case JCTree.MUL:
                result = F.fcn(F.symbol("*",null), args, null);
                break;
            case JCTree.DIV:
                result = F.fcn(F.symbol("/",null), args, null);
                break;
            case JCTree.MOD:
                result = F.fcn(F.symbol("mod",null), args, null);
                break;
//            case JCTree.SL:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
//            case JCTree.SR:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
//            case JCTree.USR:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
//            case JCTree.BITAND:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
//            case JCTree.BITOR:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
//            case JCTree.BITXOR:
//                result = F.fcn(F.symbol("or",null), args, null);
//                break;
            default:
                log.error("jml.internal","Don't know how to translate expression to SMTLIB: " + JmlPretty.write(tree));
                throw new RuntimeException();
        }
    }

    public void visitTypeCast(JCTypeCast tree) {
        notImpl(tree);
        super.visitTypeCast(tree);
    }

    public void visitTypeTest(JCInstanceOf tree) {
        notImpl(tree);
        super.visitTypeTest(tree);
    }

    public void visitIndexed(JCArrayAccess tree) {
        notImpl(tree);
        super.visitIndexed(tree);
    }

    public void visitSelect(JCFieldAccess tree) {
        // o.f becomes f[o] where f has sort (Array REF type)
        doFieldAccess(tree.selected,tree.sym);
    }
    
    protected void doFieldAccess(JCExpression object, Symbol field) {
        if (true) {
            ISort arrsort = F.createSortExpression(F.symbol("Array", null),refSort,convertSort(field.type));
            List<ISort> args = new LinkedList<ISort>();
            ICommand c = new C_declare_fun(F.symbol(field.name.toString(),null),
                    args,arrsort);
            script.add(c);
        }
        result = F.fcn(F.symbol("select", null),F.symbol(field.name.toString(),null),
                object == null ? thisRef: convertExpr(object));
        
    }

    public void visitIdent(JCIdent tree) {
        if (tree.sym != null && tree.sym.owner instanceof ClassSymbol && tree.sym.name != names._this) {
            // a select from this
            doFieldAccess(null,tree.sym);
        } else {
            result = F.symbol(tree.name.toString(),null);
        } 
    }

    public void visitLiteral(JCLiteral tree) {
        // FIXME - need real, double, char, byte
        if (tree.typetag == TypeTags.BOOLEAN) {
           result = F.symbol("true",null); 
        } else if (tree.typetag == TypeTags.INT) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.LONG) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.SHORT) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.BOT) {
            result = nullRef;
        } else {
            notImpl(tree);
            super.visitLiteral(tree);
        }
    }

    public void visitTypeIdent(JCPrimitiveTypeTree tree) {
        notImpl(tree);
        super.visitTypeIdent(tree);
    }

    public void visitTypeArray(JCArrayTypeTree tree) {
        notImpl(tree);
        super.visitTypeArray(tree);
    }

    public void visitTypeApply(JCTypeApply tree) {
        notImpl(tree);
        super.visitTypeApply(tree);
    }

    public void visitTypeDisjunction(JCTypeDisjunction tree) {
        notImpl(tree);
        super.visitTypeDisjunction(tree);
    }

    public void visitTypeParameter(JCTypeParameter tree) {
        notImpl(tree);
        super.visitTypeParameter(tree);
    }

    @Override
    public void visitWildcard(JCWildcard tree) {
        notImpl(tree);
        super.visitWildcard(tree);
    }

    @Override
    public void visitTypeBoundKind(TypeBoundKind tree) {
        notImpl(tree);
        super.visitTypeBoundKind(tree);
    }

}
