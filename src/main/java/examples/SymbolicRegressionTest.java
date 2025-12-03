package examples;

import static io.jenetics.util.RandomRegistry.random;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import com.sun.tools.javac.Main;

import cellular.CellularEngine;
import cellular.GraphMap;
import cellular.GraphMaps;
import io.jenetics.engine.Codec;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.EvolutionStatistics;
import io.jenetics.engine.EvolutionStream;
import io.jenetics.ext.util.Tree;
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
import io.jenetics.stat.MinMax;
import io.jenetics.util.ISeq;

public class SymbolicRegressionTest {

	private static final ISeq<Op<Double>> OPS = ISeq.of(MathOp.ADD, MathOp.SUB, MathOp.MUL, MathOp.DIV, MathOp.SQRT,
			MathOp.SIN, MathOp.COS);

	public static void main(String... args) {

		try {

			final Regression<Double> regression = generateProblem("feynman_I_10_7.tsv.gz");

			String output = evolve(regression, GraphMaps.barabasiAlbert(100, 5, 3));
//			final EvolutionResult<ProgramGene<Double>, Double> er = engine.stream().limit(50).peek(statistics)
//					.collect(EvolutionResult.toBestEvolutionResult());
//
//			final ProgramGene<Double> gene = er.bestPhenotype().genotype().gene();
//
//			TreeNode<Op<Double>> tree = gene.toTreeNode();
//
//			MathExpr.rewrite(tree);

			writeOutput(output, // statistics.tsoString(),
					"feynman_I_10_7.txt");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static String evolve(Regression<Double> problem, GraphMap connections) {
		CellularEngine engine = new CellularEngine(connections, problem);
		EvolutionStatistics<Double, MinMax<Double>> statistics = EvolutionStatistics.ofComparable();
		EvolutionResult<ProgramGene<Double>, Double> er = EvolutionStream
				.ofEvolution(() -> engine.start(connections.getConnections().size(), 1), engine::evolve).limit(10)
				.peek(statistics).collect(EvolutionResult.toBestEvolutionResult());

		TreeNode<Op<Double>> tree = er.bestPhenotype().genotype().gene().toTreeNode();

		MathExpr.rewrite(tree);

		return String.format("%s%n%n%s", new MathExpr(tree).toString(), statistics.toString());
	}

	private static Regression<Double> generateProblem(String fileName) throws IOException {
		String filepath = Objects.requireNonNull(Main.class.getClassLoader().getResource(fileName)).getFile();

		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(new FileInputStream(filepath))))) {
			String header = br.readLine();

			String[] vars = header.split("\t");

			ArrayList<Op<Double>> terminals = new ArrayList<>(vars.length);
			for (int i = 0; i < vars.length - 1; i++) {
				terminals.add(Var.of(vars[i], i));
			}
			terminals.add(EphemeralConst.of(() -> random().nextDouble(10)));

			final String csvData = br.readAllAsString().replace("\t", ", ");
			List<Sample<Double>> samples = Sample.parseDoubles(csvData);
			Codec<Tree<Op<Double>, ?>, ProgramGene<Double>> codec = Regression.codecOf(OPS, ISeq.of(terminals), 5,
					gt -> gt.gene().size() < 50);
			return Regression.of(codec, Error.of(LossFunction::mse), samples);
		}
	}

	private static void writeOutput(String output, String outFilename) {
		try {
			File dataDir = new File("data");

			if (!dataDir.exists() && !dataDir.mkdir()) {
				throw new IOException("Impossible to craete 'data' foler");
			}

			File outFile = new File(dataDir, outFilename);
			if (!outFile.exists() && !outFile.createNewFile()) {
				throw new IOException(String.format("Impossible to create '%s'", outFilename));
			}
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
				bw.write(output);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
