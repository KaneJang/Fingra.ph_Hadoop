/**
 * Copyright 2014 tgrape Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ph.fingra.hadoop.mapred.parts.performance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
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

import ph.fingra.hadoop.common.ConstantVars;
import ph.fingra.hadoop.common.FingraphConfig;
import ph.fingra.hadoop.common.HfsPathInfo;
import ph.fingra.hadoop.common.LfsPathInfo;
import ph.fingra.hadoop.common.ConstantVars.DataUsable;
import ph.fingra.hadoop.common.ConstantVars.LogParserType;
import ph.fingra.hadoop.common.ConstantVars.LogValidation;
import ph.fingra.hadoop.common.domain.TargetDate;
import ph.fingra.hadoop.common.logger.ErrorLogger;
import ph.fingra.hadoop.common.logger.WorkLogger;
import ph.fingra.hadoop.common.util.ArgsOptionUtil;
import ph.fingra.hadoop.common.util.ConvertTimeZone;
import ph.fingra.hadoop.common.util.FormatUtil;
import ph.fingra.hadoop.mapred.common.CopyToLocalFile;
import ph.fingra.hadoop.mapred.common.HdfsFileUtil;
import ph.fingra.hadoop.mapred.parse.CommonLogParser;

public class PageviewStatistic extends Configured implements Tool {
    
    @Override
    public int run(String[] args) throws Exception {
        
        String opt_mode = "";
        String opt_target = "";
        int opt_numreduce = 0;
        
        FingraphConfig fingraphConfig = new FingraphConfig();
        TargetDate targetDate = null;
        
        Configuration conf = getConf();
        Path[] inputPaths = null;
        Path outputPath = null;
        
        // get -D optional value
        opt_mode = conf.get(ConstantVars.DOPTION_RUNMODE, "");
        opt_target = conf.get(ConstantVars.DOPTION_TARGETDATE, "");
        opt_numreduce = conf.getInt(ConstantVars.DOPTION_NUMREDUCE, 0);
        
        // runmode & targetdate check
        if (ArgsOptionUtil.checkRunmode(opt_mode)==false) {
            throw new Exception("option value of -Drunmode is not correct");
        }
        if (opt_target.isEmpty()==false) {
            if (ArgsOptionUtil.checkTargetDateByMode(opt_mode, opt_target)==false) {
                throw new Exception("option value of -Dtargetdate is not correct");
            }
        }
        else {
            opt_target = ArgsOptionUtil.getDefaultTargetDateByMode(opt_mode);
        }
        
        // get TargetDate info from opt_target
        targetDate = ArgsOptionUtil.getTargetDate(opt_mode, opt_target);
        
        WorkLogger.log(PageviewStatistic.class.getSimpleName()
                + " : [run mode] " + opt_mode
                + " , [target date] " + targetDate.getFulldate()
                + " , [reducer count] " + opt_numreduce);
        
        // get this job's output path
        HfsPathInfo hfsPath = new HfsPathInfo(fingraphConfig, opt_mode);
        outputPath = new Path(hfsPath.getPageview());
        
        // delete previous output path if is exist
        FileSystem fs = FileSystem.get(conf);
        List<Path> deletePaths = new ArrayList<Path>();
        deletePaths.add(outputPath);
        for (Path deletePath : deletePaths) {
            fs.delete(deletePath, true);
        }
        
        int status = 0;
        if (opt_mode.equals(ConstantVars.RUNMODE_HOUR)) {
            
            // get this job's input path - original log file
            inputPaths = HdfsFileUtil.getOriginInputPaths(fingraphConfig, opt_mode,
                    targetDate.getYear(), targetDate.getMonth(), targetDate.getDay(),
                    targetDate.getHour(), targetDate.getWeek());
            if (inputPaths==null) {
                WorkLogger.log("There is no input data.");
                return 0;
            }
            
            Job job = createHourJob(conf, inputPaths, outputPath, opt_numreduce,
                    fingraphConfig, targetDate);
            
            status = job.waitForCompletion(true) ? 0 : 1;
        }
        else {
            
            // get this job's input path - transform log file
            inputPaths = HdfsFileUtil.getTransformInputPaths(fingraphConfig, opt_mode,
                    targetDate.getYear(), targetDate.getMonth(), targetDate.getDay(),
                    targetDate.getHour(), targetDate.getWeek());
            if (inputPaths==null) {
                WorkLogger.log("There is no input data.");
                return 0;
            }
            
            Job job = createJob(conf, inputPaths, outputPath, opt_numreduce,
                    fingraphConfig);
            
            status = job.waitForCompletion(true) ? 0 : 1;
        }
        
        // copy to local result paths
        LfsPathInfo lfsPath = new LfsPathInfo(fingraphConfig, targetDate);
        CopyToLocalFile copier = new CopyToLocalFile();
        copier.dirToFile(outputPath.toString(), lfsPath.getPageview());
        
        return status;
    }
    
    public Job createJob(Configuration conf, Path[] inputpaths, Path outputpath,
            int numreduce, FingraphConfig finconfig) throws IOException {
        
        conf.setBoolean("verbose", finconfig.getDebug().isDebug_show_verbose());
        conf.setBoolean("counter", finconfig.getDebug().isDebug_show_counter());
        
        Job job = new Job(conf);
        String jobName = "perform/pageview job";
        job.setJobName(jobName);
        
        job.setJarByClass(PageviewStatistic.class);
        
        for (int i=0; i<inputpaths.length; i++) {
            FileInputFormat.addInputPath(job, inputpaths[i]);
        }
        FileOutputFormat.setOutputPath(job, outputpath);
        
        job.setMapperClass(PageviewMapper.class);
        job.setCombinerClass(PageviewReducer.class);
        job.setReducerClass(PageviewReducer.class);
        
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(LongWritable.class);
        
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(LongWritable.class);
        
        job.setPartitionerClass(PageviewPartitioner.class);
        
        job.setNumReduceTasks(numreduce);
        
        return job;
    }
    
    static class PageviewMapper
        extends Mapper<LongWritable, Text, Text, LongWritable> {
        
        private boolean verbose = false;
        private boolean counter = false;
        
        private CommonLogParser commonparser = new CommonLogParser();
        
        private Text out_key = new Text();
        private LongWritable out_val = new LongWritable(1);
        
        protected void setup(Context context)
                throws IOException, InterruptedException {
            verbose = context.getConfiguration().getBoolean("verbose", false);
            counter = context.getConfiguration().getBoolean("counter", false);
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            
            // logtype check
            LogParserType logtype = FormatUtil.getLogParserType(value.toString());
            
            if (logtype.equals(LogParserType.CommonLog)) {
                
                // CommonLog : STARTSESS/PAGEVIEW/ENDSESS
                commonparser.parse(value);
                if (commonparser.hasError() == false) {
                    
                    /*
                     * Let the STARTSESS signal considered to be called PAGEVIEW
                     */
                    
                    if (commonparser.getCmd().equals(ConstantVars.CMD_STARTSESS)
                            || commonparser.getCmd().equals(ConstantVars.CMD_PAGEVIEW)) {
                        out_key.set(commonparser.getAppkey());
                        
                        context.write(out_key, out_val);
                    }
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(commonparser.getErrorLevel()).increment(1);
            }
            else if (logtype.equals(LogParserType.ComponentLog)) {
                
                // ComponentLog : COMPONENT
                
                if (counter)
                    context.getCounter(DataUsable.USELESS).increment(1);
            }
            else {
                if (verbose)
                    System.err.println("Ignoring corrupt input: " + value);
                if (counter)
                    context.getCounter(LogValidation.MALFORMED).increment(1);
            }
        }
    }
    
    static class PageviewReducer
        extends Reducer<Text, LongWritable, Text, LongWritable> {
        
        private Text out_key = new Text();
        private LongWritable out_val = new LongWritable(0);
        
        @Override
        protected void reduce(Text key, Iterable<LongWritable> values,
                Context context) throws IOException, InterruptedException {
            
            long sum = 0;
            for (LongWritable cur_val : values) {
                sum += cur_val.get();
            }
            
            out_key.set(key);
            out_val.set(sum);
            
            context.write(out_key, out_val);
        }
    }
    
    private static class PageviewPartitioner
        extends Partitioner<Text, LongWritable> {
        @Override
        public int getPartition(Text key, LongWritable value,
                int numPartitions) {
            return Math.abs(key.hashCode() * 127) % numPartitions;
        }
    }
    
    public Job createHourJob(Configuration conf, Path[] inputpaths,
            Path outputpath, int numreduce, FingraphConfig finconfig,
            TargetDate targetdate) throws IOException {
        
        conf.setBoolean("verbose", finconfig.getDebug().isDebug_show_verbose());
        conf.setBoolean("counter", finconfig.getDebug().isDebug_show_counter());
        conf.set("hour", targetdate.getHour());
        
        Job job = new Job(conf);
        String jobName = "perform/pageview hour job";
        job.setJobName(jobName);
        
        job.setJarByClass(PageviewStatistic.class);
        
        for (int i=0; i<inputpaths.length; i++) {
            FileInputFormat.addInputPath(job, inputpaths[i]);
        }
        FileOutputFormat.setOutputPath(job, outputpath);
        
        job.setMapperClass(PageviewHourMapper.class);
        job.setCombinerClass(PageviewHourReducer.class);
        job.setReducerClass(PageviewHourReducer.class);
        
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(LongWritable.class);
        
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(LongWritable.class);
        
        job.setPartitionerClass(PageviewHourPartitioner.class);
        
        job.setNumReduceTasks(numreduce);
        
        return job;
    }
    
    static class PageviewHourMapper
        extends Mapper<LongWritable, Text, Text, LongWritable> {
        
        private boolean verbose = false;
        private boolean counter = false;
        private String target_hour = "";
        
        private int LTIME_LENGTH = ConstantVars.LOG_DATE_FORMAT.length();
        private int HOUR_INDEX = 8;
        private int HOUR_LENGTH = 2;
        
        private CommonLogParser commonparser = new CommonLogParser();
        
        private ConvertTimeZone timeZone = new ConvertTimeZone();
        
        private Text out_key = new Text();
        private LongWritable out_val = new LongWritable(1);
        
        protected void setup(Context context)
                throws IOException, InterruptedException {
            verbose = context.getConfiguration().getBoolean("verbose", false);
            counter = context.getConfiguration().getBoolean("counter", false);
            target_hour = context.getConfiguration().get("hour");
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            
            // logtype check
            LogParserType logtype = FormatUtil.getLogParserType(value.toString());
            
            if (logtype.equals(LogParserType.CommonLog)) {
                
                // CommonLog : STARTSESS/PAGEVIEW/ENDSESS
                commonparser.parse(value);
                if (commonparser.hasError() == false) {
                    
                    /*
                     * Let the STARTSESS signal considered to be called PAGEVIEW
                     */
                    
                    if (commonparser.getCmd().equals(ConstantVars.CMD_STARTSESS)
                            || commonparser.getCmd().equals(ConstantVars.CMD_PAGEVIEW)) {
                        
                        // hour : hour from converted utctime to server operation time
                        boolean rise_error = false;
                        String utime2ltime = "";
                        String utime2ltime_hour = "";
                        try {
                            utime2ltime = timeZone.convertUtcToLocal(commonparser.getUtctime());
                        }
                        catch (Exception e) {
                            rise_error = true;
                        }
                        if (utime2ltime.length()==LTIME_LENGTH) {
                            utime2ltime_hour = utime2ltime.substring(HOUR_INDEX, HOUR_INDEX+HOUR_LENGTH);
                        }
                        else {
                            rise_error = true;
                        }
                        
                        if (rise_error==false
                                && utime2ltime_hour.equals(target_hour)) {
                            out_key.set(commonparser.getAppkey());
                            
                            context.write(out_key, out_val);
                        }
                    }
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(commonparser.getErrorLevel()).increment(1);
            }
            else if (logtype.equals(LogParserType.ComponentLog)) {
                
                // ComponentLog : COMPONENT
                
                if (counter)
                    context.getCounter(DataUsable.USELESS).increment(1);
            }
            else {
                if (verbose)
                    System.err.println("Ignoring corrupt input: " + value);
                if (counter)
                    context.getCounter(LogValidation.MALFORMED).increment(1);
            }
        }
    }
    
    static class PageviewHourReducer
        extends Reducer<Text, LongWritable, Text, LongWritable> {
        
        private Text out_key = new Text();
        private LongWritable out_val = new LongWritable(0);
        
        @Override
        protected void reduce(Text key, Iterable<LongWritable> values,
                Context context) throws IOException, InterruptedException {
            
            long sum = 0;
            for (LongWritable cur_val : values) {
                sum += cur_val.get();
            }
            
            out_key.set(key);
            out_val.set(sum);
            
            context.write(out_key, out_val);
        }
    }
    
    private static class PageviewHourPartitioner
        extends Partitioner<Text, LongWritable> {
        @Override
        public int getPartition(Text key, LongWritable value,
                int numPartitions) {
            return Math.abs(key.hashCode() * 127) % numPartitions;
        }
    }
    
    /**
     * 
     * @param args
     */
    public static void main(String[] args) {
        
        long start_time=0, end_time=0;
        int exitCode = 0;
        
        start_time = System.currentTimeMillis();
        
        WorkLogger.log(PageviewStatistic.class.getSimpleName()
                + " : Start mapreduce job");
        
        try {
            exitCode = ToolRunner.run(new PageviewStatistic(), args);
            
            WorkLogger.log(PageviewStatistic.class.getSimpleName()
                    + " : End mapreduce job");
        }
        catch (Exception e) {
            ErrorLogger.log(PageviewStatistic.class.getSimpleName()
                    + " : Error : " + e.getMessage());
            WorkLogger.log(PageviewStatistic.class.getSimpleName()
                    + " : Failed mapreduce job");
        }
        
        end_time = System.currentTimeMillis();
        
        try {
            FingraphConfig config = new FingraphConfig();
            if (config.getDebug().isDebug_show_spenttime())
                WorkLogger.log("DEBUG - run times : "
                        + FormatUtil.getDurationFromMillitimes(end_time - start_time));
        }
        catch (IOException ignore) {}
        
        System.exit(exitCode);
    }
}
