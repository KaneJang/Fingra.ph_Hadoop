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

package ph.fingra.hadoop.mapred.parts.performance.domain;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.commons.lang.builder.HashCodeBuilder;

import ph.fingra.hadoop.mapred.common.BaseWritableComparable;

public class SesstimeKey extends BaseWritableComparable<SesstimeKey> {
    
    public String appkey = "";
    public String session = "";
    public String utctime = "";
    
    public void set(String appkey, String session, String utctime) {
        
        this.appkey = appkey;
        this.session = session;
        this.utctime = utctime;
    }
    
    @Override
    public void readFields(DataInput in) throws IOException {
        
        this.appkey = in.readUTF();
        this.session = in.readUTF();
        this.utctime = in.readUTF();
    }
    
    @Override
    public void write(DataOutput out) throws IOException {
        
        out.writeUTF(this.appkey);
        out.writeUTF(this.session);
        out.writeUTF(this.utctime);
    }
    
    @Override
    public int compareTo(SesstimeKey o) {
        
        int ret = 0;
        
        ret = this.appkey.compareTo(o.appkey); if (ret != 0) return ret;
        ret = this.session.compareTo(o.session); if (ret != 0) return ret;
        ret = this.utctime.compareTo(o.utctime);
        
        return ret;
    }
    
    @Override
    public int hashCode() {
        
        return HashCodeBuilder.reflectionHashCode(this);
    }
}
