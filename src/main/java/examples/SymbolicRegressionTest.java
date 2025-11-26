package examples;

import static io.jenetics.util.RandomRegistry.random;

import java.util.List;

import io.jenetics.Mutator;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.Limits;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.util.TreeNode;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.EphemeralConst;
import io.jenetics.prog.op.MathExpr;
import io.jenetics.prog.op.MathOp;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.op.Var;
import io.jenetics.prog.regression.Error;
import io.jenetics.prog.regression.LossFunction;
import io.jenetics.prog.regression.Regression;
import io.jenetics.prog.regression.Sample;
import io.jenetics.util.ISeq;

public class SymbolicRegressionTest {

	private static final ISeq<Op<Double>> OPS = ISeq.of(MathOp.ADD, MathOp.MUL, MathOp.MUL);

	private static final ISeq<Op<Double>> TMS = ISeq.of(Var.of("x", 0),
			EphemeralConst.of(() -> (double) random().nextInt(10)));

	public static final List<Sample<Double>> SAMPLES = Sample.parseDoubles("""
			-1.0, -8.0000
			-0.9, -6.2460
			-0.8, -4.7680
			-0.7, -3.5420
			-0.6, -2.5440
			-0.5, -1.7500
			-0.4, -1.1360
			-0.3, -0.6780
			-0.2, -0.3520
			-0.1, -0.1340
			 0.0,  0.0000
			 0.1,  0.0740
			 0.2,  0.1120
			 0.3,  0.1380
			 0.4,  0.1760
			 0.5,  0.2500
			 0.6,  0.3840
			 0.7,  0.6020
			 0.8,  0.9280
			 0.9,  1.3860
			 1.0,  2.0000
			""");

	static final Regression<Double> REGRESSION = Regression
			.of(Regression.codecOf(OPS, TMS, 5, t -> t.gene().size() < 30), Error.of(LossFunction::mse), SAMPLES);

	public static void main(String... args) {
		final Engine<ProgramGene<Double>, Double> engine = Engine.builder(REGRESSION).minimizing()
				.alterers(new SingleNodeCrossover<>(0.1), new Mutator<>()).build();

		final EvolutionResult<ProgramGene<Double>, Double> er = engine.stream().limit(Limits.byFixedGeneration(1000000))
				.collect(EvolutionResult.toBestEvolutionResult());

		final ProgramGene<Double> gene = er.bestPhenotype().genotype().gene();

		TreeNode<Op<Double>> tree = gene.toTreeNode();

		MathExpr.rewrite(tree);

		System.out.println("G" + er.totalGenerations());
		System.out.println("F" + new MathExpr(tree));
		System.out.println("E" + REGRESSION.error(tree));

	}
}