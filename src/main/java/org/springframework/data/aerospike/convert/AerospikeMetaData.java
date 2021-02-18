/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.aerospike.convert;

/**
 * Carries metadata keys of an aerospike read or written object.
 * @author Anastasiia Smirnova
 */
public interface AerospikeMetaData {

	//sometimes aerospike does not retrieve userKey
	//so we need to save it as a Bin
	//see https://github.com/aerospike/aerospike-client-java/issues/77
	String USER_KEY = "@user_key";

}
