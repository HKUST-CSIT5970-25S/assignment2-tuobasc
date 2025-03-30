package hk.ust.csit5970;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

/**
 * Compute the bigram count using "pairs" approach
 */
public class BigramFrequencyPairs extends Configured implements Tool {
	private static final Logger LOG = Logger.getLogger(BigramFrequencyPairs.class);

	/*
	 * TODO: write your Mapper here.
	 */
	private static class MyMapper extends
			Mapper<LongWritable, Text, PairOfStrings, IntWritable> {

		// Reuse objects to save overhead of object creation.
		private static final IntWritable ONE = new IntWritable(1);
		private static final PairOfStrings BIGRAM = new PairOfStrings();

		@Override
		public void map(LongWritable key, Text value, Context context)
				throws IOException, InterruptedException {
			String line = ((Text) value).toString();
			// line = line.replaceAll("[^a-zA-Z0-9\\s]", ""); // don't know whether to remove
			String[] words = line.trim().split("\\s+");
			
			/*
			 * TODO: Your implementation goes here.
			 */

            for (int i = 0; i < words.length - 1; i++) {
                String w1 = words[i];
                String w2 = words[i + 1];

                // output (w1, w2) -> 1
                BIGRAM.set(w1, w2);
                context.write(BIGRAM, ONE);

                // word count (w1, *) -> 1
                BIGRAM.set(w1, "*");
                context.write(BIGRAM, ONE);
            }
		}
	}

	/*
	 * TODO: Write your reducer here.
	 */
	private static class MyReducer extends
			Reducer<PairOfStrings, IntWritable, PairOfStrings, FloatWritable> {

		// Reuse objects.
		private final static FloatWritable VALUE = new FloatWritable();
		private String currentLeftWord = null;
        private int currentTotalCount = 0;

		@Override
		public void reduce(PairOfStrings key, Iterable<IntWritable> values,
				Context context) throws IOException, InterruptedException {
			/*
			 * TODO: Your implementation goes here.
			 */
			// check left word, update when new word
            if (!key.getLeftElement().equals(currentLeftWord)) {
                currentLeftWord = key.getLeftElement();
                currentTotalCount = 0;
            }

            // calculate the count of every key, (a, b) -> 1 + (a,b) -> 2,
            int sum = 0;
            for (IntWritable val : values) {
                sum += val.get();
            }

            // Maybe the single word
            if (key.getRightElement().equals("*")) {
                currentTotalCount = sum;
                // output the single word count
                PairOfStrings outputKey = new PairOfStrings();
                outputKey.set(currentLeftWord, "");
                VALUE.set((float) currentTotalCount);
                context.write(outputKey, VALUE);
            } else {
                // calculate count(A, B) / count(A, *)
                if (currentTotalCount != 0) {
                    float relativeFrequency = (float) sum / currentTotalCount;
                    VALUE.set(relativeFrequency);
                    // output the frequency
                    context.write(key, VALUE);
                }
            }
		}
	}
	
	private static class MyCombiner extends
			Reducer<PairOfStrings, IntWritable, PairOfStrings, IntWritable> {
		private static final IntWritable SUM = new IntWritable();

		@Override
		public void reduce(PairOfStrings key, Iterable<IntWritable> values,
				Context context) throws IOException, InterruptedException {
			/*
			 * TODO: Your implementation goes here.
			 */
		    int sum = 0;
            // accumulate the values
            for (IntWritable val : values) {
                sum += val.get();
            }
            SUM.set(sum);
            context.write(key, SUM);
		}
	}

	/*
	 * Partition bigrams based on their left elements
	 */
	private static class MyPartitioner extends
			Partitioner<PairOfStrings, IntWritable> {
		@Override
		public int getPartition(PairOfStrings key, IntWritable value,
				int numReduceTasks) {
			return (key.getLeftElement().hashCode() & Integer.MAX_VALUE)
					% numReduceTasks;
		}
	}

	/**
	 * Creates an instance of this tool.
	 */
	public BigramFrequencyPairs() {
	}

	private static final String INPUT = "input";
	private static final String OUTPUT = "output";
	private static final String NUM_REDUCERS = "numReducers";

	/**
	 * Runs this tool.
	 */
	@SuppressWarnings({ "static-access" })
	public int run(String[] args) throws Exception {
		Options options = new Options();

		options.addOption(OptionBuilder.withArgName("path").hasArg()
				.withDescription("input path").create(INPUT));
		options.addOption(OptionBuilder.withArgName("path").hasArg()
				.withDescription("output path").create(OUTPUT));
		options.addOption(OptionBuilder.withArgName("num").hasArg()
				.withDescription("number of reducers").create(NUM_REDUCERS));

		CommandLine cmdline;
		CommandLineParser parser = new GnuParser();

		try {
			cmdline = parser.parse(options, args);
		} catch (ParseException exp) {
			System.err.println("Error parsing command line: "
					+ exp.getMessage());
			return -1;
		}

		// Lack of arguments
		if (!cmdline.hasOption(INPUT) || !cmdline.hasOption(OUTPUT)) {
			System.out.println("args: " + Arrays.toString(args));
			HelpFormatter formatter = new HelpFormatter();
			formatter.setWidth(120);
			formatter.printHelp(this.getClass().getName(), options);
			ToolRunner.printGenericCommandUsage(System.out);
			return -1;
		}

		String inputPath = cmdline.getOptionValue(INPUT);
		String outputPath = cmdline.getOptionValue(OUTPUT);
		int reduceTasks = cmdline.hasOption(NUM_REDUCERS) ? Integer
				.parseInt(cmdline.getOptionValue(NUM_REDUCERS)) : 1;

		LOG.info("Tool: " + BigramFrequencyPairs.class.getSimpleName());
		LOG.info(" - input path: " + inputPath);
		LOG.info(" - output path: " + outputPath);
		LOG.info(" - number of reducers: " + reduceTasks);

		// Create and configure a MapReduce job
		Configuration conf = getConf();
		Job job = Job.getInstance(conf);
		job.setJobName(BigramFrequencyPairs.class.getSimpleName());
		job.setJarByClass(BigramFrequencyPairs.class);

		job.setNumReduceTasks(reduceTasks);

		FileInputFormat.setInputPaths(job, new Path(inputPath));
		FileOutputFormat.setOutputPath(job, new Path(outputPath));

		job.setMapOutputKeyClass(PairOfStrings.class);
		job.setMapOutputValueClass(IntWritable.class);
		job.setOutputKeyClass(PairOfStrings.class);
		job.setOutputValueClass(FloatWritable.class);

		/*
		 * A MapReduce program consists of three components: a mapper, a
		 * reducer, a combiner (which reduces the amount of shuffle data), and a partitioner
		 */
		job.setMapperClass(MyMapper.class);
		job.setCombinerClass(MyCombiner.class);
		job.setPartitionerClass(MyPartitioner.class);
		job.setReducerClass(MyReducer.class);

		// Delete the output directory if it exists already.
		Path outputDir = new Path(outputPath);
		FileSystem.get(conf).delete(outputDir, true);

		// Time the program
		long startTime = System.currentTimeMillis();
		job.waitForCompletion(true);
		LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
				/ 1000.0 + " seconds");

		return 0;
	}

	/**
	 * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
	 */
	public static void main(String[] args) throws Exception {
		ToolRunner.run(new BigramFrequencyPairs(), args);
	}
}