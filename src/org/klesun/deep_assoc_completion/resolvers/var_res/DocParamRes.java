package org.klesun.deep_assoc_completion.resolvers.var_res;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.jetbrains.php.lang.PhpLanguage;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocParamTag;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocTag;
import com.jetbrains.php.lang.psi.elements.GroupStatement;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.Statement;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.klesun.deep_assoc_completion.DeepType;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.MultiType;
import org.klesun.lang.L;
import org.klesun.lang.Lang;
import org.klesun.lang.Opt;
import org.klesun.lang.Tls;

public class DocParamRes extends Lang
{
    private FuncCtx ctx;

    public DocParamRes(FuncCtx ctx)
    {
        this.ctx = ctx;
    }

    private static class PropDoc
    {
        final String type;
        final String name;
        String desc;

        PropDoc(String type, String name, String desc) {
            this.type = type;
            this.name = name;
            this.desc = desc;
        }
    }

    private static Opt<String> getDocCommentText(PhpDocComment docComment)
    {
        return Tls.regex("\\s*\\/\\*{2}\\s*(.*?)\\s*\\*\\/", docComment.getText())
            .fop(matches -> matches.gat(0))
            .fap(starred -> L(starred.split("\n")))
            .fop(line -> Tls.regex("\\s*\\*(.*)", line))
            .fop(matches -> matches.gat(0))
            .wap(cleanLines -> opt(Tls.implode("\n", cleanLines)));
    }

    private static boolean nameMatches(PropDoc propDoc, PhpDocTag docTag)
    {
        return Tls.cast(PhpDocParamTag.class, docTag)
            .map(pt -> pt.getVarName())
            .flt(nme -> nme.equals(propDoc.name))
            .has();
    }

    /**
     * @var stdClass $row {
     *      @property int id some description
     *      @property string name some description
     *      @property string childPurchase {
     *          @property int id some description
     *          @property float price
     *      }
     * }
     */
    private static L<PropDoc> parseObjDoc(String body)
    {
        Mutable<Integer> depth = new Mutable<>(0);
        L<PropDoc> props = L();
        for (String line: body.split("\n")) {
            if (depth.get() == 0) {
                Tls.regex("\\s*@(property|var|param)\\s+([A-Za-z\\d\\\\_]+)\\s+\\$?(\\w+)(.*)", line)
                    .map(matches -> new PropDoc(matches.get(1), matches.get(2), matches.get(3)))
                    .thn(prop -> {
                        props.add(prop);
                        if (Tls.regex(".*\\{\\s*", prop.desc).has()) {
                            depth.set(depth.get() + 1);
                        }
                    })
                    .els(() -> props.lst().thn(prop -> prop.desc += "\n" + line));
            } else {
                props.lst().thn(prop -> prop.desc += "\n" + line);
                if (Tls.regex("\\s*\\}\\s*", line).has()) {
                    depth.set(depth.get() - 1);
                }
            }
        }
        return props;
    }

    private static Opt<DeepType> propDescToType(String propDesc, PsiElement decl)
    {
        L<PropDoc> props = parseObjDoc(propDesc);
        if (props.size() > 0) {
            DeepType type = new DeepType(decl, PhpType.OBJECT);
            props.fch(prop -> type.addProp(prop.name, decl)
                .addType(
                    () -> propDescToType(prop.desc, decl)
                        .map(t -> new MultiType(list(t)))
                        .def(MultiType.INVALID_PSI),
                    new PhpType().add("\\" + prop.type))
                );

            return opt(type);
        } else {
            return opt(null);
        }
    }

    public static Opt<MultiType> parseExpression(String expr, Project project, FuncCtx docCtx)
    {
        // adding "$arg = " so anonymous functions were parsed as expressions
        expr = "<?php\n$arg = " + expr + ";";
        PsiFile psiFile = PsiFileFactory.getInstance(project).createFileFromText(PhpLanguage.INSTANCE, expr);
        return opt(psiFile.getFirstChild())
            .fop(toCast(GroupStatement.class))
            .map(gr -> gr.getFirstPsiChild())
            .fop(toCast(Statement.class))
            .map(st -> st.getFirstChild())
            .fop(toCast(PhpExpression.class))
            .map(ex -> docCtx.findExprType(ex));
    }

    private Opt<MultiType> parseDoc(PhpDocTag doc, Project project)
    {
        String tagValue = doc.getTagValue();
        FuncCtx docCtx = new FuncCtx(ctx.getSearch());
        docCtx.fakeFileSource = opt(doc);
        return Opt.fst(
            () -> Tls.regex("^\\s*=\\s*(.+)$", tagValue)
                .fop(matches -> matches.gat(0))
                .fop(expr -> parseExpression(expr, project, docCtx)),
            () -> opt(doc.getParent())
                .fop(toCast(PhpDocComment.class))
                .fop(full -> getDocCommentText(full))
                .fap(clean -> parseObjDoc(clean))
                .flt(prop -> nameMatches(prop, doc))
                .fop(prop -> propDescToType(prop.desc, doc))
                .wap(types -> opt(types))
                .flt(types -> types.size() > 0)
                .map(types -> new MultiType(types))
        );
    }

    public Opt<MultiType> resolve(PhpDocTag doc)
    {
        return parseDoc(doc, doc.getProject());
    }
}
