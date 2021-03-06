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

package ph.fingra.hadoop.mapred;

import org.apache.hadoop.util.ProgramDriver;

import ph.fingra.hadoop.common.logger.ErrorLogger;
import ph.fingra.hadoop.mapred.parts.performance.FrequencyStatistic;
import ph.fingra.hadoop.mapred.parts.performance.HourSessionStatistic;
import ph.fingra.hadoop.mapred.parts.performance.NewuserStatistic;
import ph.fingra.hadoop.mapred.parts.performance.PageviewStatistic;
import ph.fingra.hadoop.mapred.parts.performance.SessionLengthStatistic;
import ph.fingra.hadoop.mapred.parts.performance.UserSessionStatistic;

public class PerformanceDriver {

    public static void main(String argv[]) {
        
        int exitcode = -1;
        
        ProgramDriver pgd = new ProgramDriver();
        try {
            
            pgd.addClass("newuser", NewuserStatistic.class,
                    "Fingraph OSS map/reduce program for perform/newuser");
            pgd.addClass("usersession", UserSessionStatistic.class,
                    "Fingraph OSS map/reduce program for perform/usersession");
            pgd.addClass("frequency", FrequencyStatistic.class,
                    "Fingraph OSS map/reduce program for perform/frequency");
            pgd.addClass("hoursession", HourSessionStatistic.class,
                    "Fingraph OSS map/reduce program for perform/hoursession");
            pgd.addClass("sessionlength", SessionLengthStatistic.class,
                    "Fingraph OSS map/reduce program for perform/sessionlength");
            pgd.addClass("pageview", PageviewStatistic.class,
                    "Fingraph OSS map/reduce program for perform/pageview");
            
            pgd.driver(argv);
            
            // seccess
            exitcode = 0;
        }
        catch(Throwable e) {
            ErrorLogger.log(e.toString());
        }
        
        System.exit(exitcode);
    }
}
