/*
* Copyright 2016 Anatoly Kutyakov
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package buckelieg.simpletools.db;

import javax.annotation.Nonnull;
import java.sql.ResultSet;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Query abstraction. Can be considered as builder for queries
 * Gives a control to set up statements and do other tunings in the future.
 */
public interface Query {

    @Nonnull
    Iterable<ResultSet> execute();

    @Nonnull
    default Stream<ResultSet> stream() {
        return StreamSupport.stream(execute().spliterator(), false);
    }

    Query batchSize(int size);

}