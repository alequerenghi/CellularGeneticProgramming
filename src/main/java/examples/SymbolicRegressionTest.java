package examples;

import static io.jenetics.util.RandomRegistry.random;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cellular.CellularEngine;
import cellular.GraphMap;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.EvolutionStatistics;
import io.jenetics.engine.EvolutionStream;
import io.jenetics.engine.Limits;
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
import io.jenetics.stat.DoubleMomentStatistics;
import io.jenetics.util.ISeq;

public class SymbolicRegressionTest {

	private static final ISeq<Op<Double>> OPS = ISeq.of(MathOp.ADD, MathOp.SUB, MathOp.MUL);

	private static final ISeq<Op<Double>> TMS = ISeq.of(Var.of("x", 0),
			EphemeralConst.of(() -> random().nextDouble(10)));

	private static final List<Sample<Double>> SAMPLES = Sample.parseDoubles("""
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

	private static final Regression<Double> REGRESSION = Regression
			.of(Regression.codecOf(OPS, TMS, 5, t -> t.gene().size() < 30), Error.of(LossFunction::mse), SAMPLES);

	private static final GraphMap GRID = (popSize, gridSize) -> {
		HashMap<Integer, List<Integer>> map = new HashMap<>();
		for (int i = 0; i < popSize; i++) {
			ArrayList<Integer> neighbors = new ArrayList<>();
			neighbors.add((i + 1) % popSize);
			neighbors.add(i - 1 < 0 ? popSize - 1 : i - 1);
			neighbors.add((i + gridSize) % popSize);
			neighbors.add(i - gridSize < 0 ? popSize - gridSize + i : i - gridSize);
			map.put(i, neighbors);
		}
		return map;
	};

	public static void main(String... args) {

		Map<Integer, List<Integer>> connections = GRID.getConnections(100, 10);

//		TreeFormatter formatter = TreeFormatter.TREE;

//		Genotype<ProgramGene<Double>> newInstance = REGRESSION.codec().encoding().newInstance();

//		@SuppressWarnings("unchecked")
//		EphemeralConst<Double> newInstance = EphemeralConst
//				.of((Supplier<Double> & Serializable) () -> random().nextDouble(10));

//		System.out.println(formatter.format(newInstance.gene()));
//
//		System.out.println(REGRESSION.fitness(newInstance));

//		try {
//			ByteArrayOutputStream baos = new ByteArrayOutputStream();
//			ObjectOutputStream oos = new ObjectOutputStream(baos);
//			oos.writeObject(newInstance);
//
//			ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
//
//			@SuppressWarnings("unchecked")
//			Genotype<ProgramGene<Double>> o = (Genotype<ProgramGene<Double>>) ois.readObject();
//
//			System.out.println(formatter.format(o.gene()));
//
//			System.out.println(REGRESSION.fitness(o));
//		} catch (IOException | ClassNotFoundException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

		final CellularEngine cellularEngine = new CellularEngine(connections::get, REGRESSION);
		final EvolutionStatistics<Double, DoubleMomentStatistics> statistics = EvolutionStatistics.ofNumber();

		EvolutionResult<ProgramGene<Double>, Double> er = EvolutionStream
				.ofEvolution(() -> cellularEngine.start(100, 1), cellularEngine::evolve)
				.limit(Limits.byFitnessThreshold(0.1)).peek(statistics)
				.collect(EvolutionResult.toBestEvolutionResult());

//		final Engine<ProgramGene<Double>, Double> engine = Engine.builder(REGRESSION).minimizing()
//				.alterers(new SingleNodeCrossover<>(0.1), new Mutator<>()).build();
//
//		final EvolutionResult<ProgramGene<Double>, Double> er = engine.stream().limit(Limits.byFitnessThreshold(0.01))
//				.limit(100).collect(EvolutionResult.toBestEvolutionResult());

		final ProgramGene<Double> gene = er.bestPhenotype().genotype().gene();

		TreeNode<Op<Double>> tree = gene.toTreeNode();

		MathExpr.rewrite(tree);

		System.out.println("G" + er.totalGenerations());
		System.out.println("F" + new MathExpr(tree));
		System.out.println("V" + gene.isValid());
		System.out.println("E" + REGRESSION.error(tree));

		System.out.println(statistics);
	}
}