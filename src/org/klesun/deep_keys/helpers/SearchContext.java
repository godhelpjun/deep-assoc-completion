package org.klesun.deep_keys.helpers;

import com.jetbrains.php.lang.psi.elements.PhpExpression;
import org.klesun.deep_keys.DeepTypeResolver;
import org.klesun.lang.Lang;
import org.klesun.lang.Opt;

public class SearchContext extends Lang
{
    // parametrized fields
    private int depth = 20;
    private int initialDepth = depth;
    private boolean debug = false;

    public SearchContext()
    {
    }

    public SearchContext setDepth(int depth)
    {
        this.depth = initialDepth = depth;
        return this;
    }

    public Opt<MultiType> findExprType(PhpExpression expr, FuncCtx funcCtx)
    {
        // i didn't figure out how to catch circular
        // references yet, gonna leave it for another time
//        if (exprTrace.contains(expr)) {
//            // circular reference
//            return opt(null);
//        }

        if (depth <= 0) {
            return opt(null);
        }
        --depth;

        if (debug) {
            for (int i = 0; i < initialDepth - depth; ++i) {
                System.out.print("| ");
            }
            System.out.println(depth + " " + expr.getText().split("\n")[0]);
        }

        Opt<MultiType> result = DeepTypeResolver.resolveIn(expr, funcCtx)
            .map(ts -> new MultiType(ts));

        ++depth;
        return result;
    }
}
