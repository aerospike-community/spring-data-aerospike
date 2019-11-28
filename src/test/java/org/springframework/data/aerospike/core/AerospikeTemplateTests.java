/*
 * Copyright 2012-2018 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.aerospike.core;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.aerospike.AsyncUtils;
import org.springframework.data.aerospike.BaseIntegrationTests;
import org.springframework.data.aerospike.SampleClasses.CustomCollectionClass;
import org.springframework.data.aerospike.SampleClasses.DocumentWithByteArray;
import org.springframework.data.aerospike.SampleClasses.DocumentWithExpiration;
import org.springframework.data.aerospike.SampleClasses.DocumentWithTouchOnRead;
import org.springframework.data.aerospike.SampleClasses.DocumentWithTouchOnReadAndExpirationProperty;
import org.springframework.data.aerospike.SampleClasses.VersionedClass;
import org.springframework.data.aerospike.SampleClasses.VersionedClassWithAllArgsConstructor;
import org.springframework.data.aerospike.repository.query.Criteria;
import org.springframework.data.aerospike.repository.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.parser.Part;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.springframework.data.aerospike.SampleClasses.EXPIRATION_ONE_MINUTE;

/**
 *
 *
 * @author Peter Milne
 * @author Jean Mercier
 * @author Anastasiia Smirnova
 * @author Roman Terentiev
 *
 */
public class AerospikeTemplateTests extends BaseIntegrationTests {

	private String id;

	@Before
	public void setUp() {
		this.id = nextId();
		cleanDb();
	}

	@After
	public void tearDown() {
		cleanDb();
	}

	//test for RecordExistsAction.REPLACE_ONLY policy
	@Test
	public void save_shouldReplaceAllBinsPresentInAerospikeWhenSavingDocument() {
		Key key = new Key(getNameSpace(), "versioned-set", id);
		VersionedClass first = new VersionedClass(id, "foo");
		template.save(first);
		addNewFieldToSavedDataInAerospike(key);

		template.save(new VersionedClass(id, "foo2", 2));

		Record record2 = client.get(new Policy(), key);
		assertThat(record2.bins.get("notPresent")).isNull();
		assertThat(record2.bins.get("field")).isEqualTo("foo2");
	}

	@Test
	public void save_shouldSaveAndSetVersion() {
		VersionedClass first = new VersionedClass(id, "foo");
		template.save(first);

		assertThat(first.version).isEqualTo(1);
		assertThat(template.findById(id, VersionedClass.class).version).isEqualTo(1);
	}

	@Test
	public void save_shouldNotSaveDocumentIfItAlreadyExistsWithZeroVersion() {
		template.save(new VersionedClass(id, "foo", 0));

		assertThatThrownBy(() -> template.save(new VersionedClass(id, "foo", 0)))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	public void save_shouldSaveDocumentWithEqualVersion() {
		template.save(new VersionedClass(id, "foo", 0));

		template.save(new VersionedClass(id, "foo", 1));
		template.save(new VersionedClass(id, "foo", 2));
	}

	@Test
	public void save_shouldFailSaveNewDocumentWithVersionGreaterThanZero() {
		assertThatThrownBy(() -> template.save(new VersionedClass(id, "foo", 5)))
				.isInstanceOf(DataRetrievalFailureException.class);
	}

	@Test
	public void save_shouldUpdateNullField() {
		VersionedClass versionedClass = new VersionedClass(id, null, 0);
		template.save(versionedClass);

		VersionedClass saved = template.findById(id, VersionedClass.class);
		template.save(saved);
	}

	@Test
	public void save_shouldUpdateNullFieldForClassWithVersionField() {
		VersionedClass versionedClass = new VersionedClass(id, "field", 0);
		template.save(versionedClass);

		VersionedClass byId = template.findById(id, VersionedClass.class);
		assertThat(byId.getField())
				.isEqualTo("field");

		template.save(new VersionedClass(id, null, byId.version));

		assertThat(template.findById(id, VersionedClass.class).getField())
				.isNull();
	}

	@Test
	public void save_shouldUpdateNullFieldForClassWithoutVersionField() {
		Person person = new Person(id,"Oliver");
		person.setFirstName("First name");
		template.insert(person);

		assertThat(template.findById(id, Person.class)).isEqualTo(person);

		person.setFirstName(null);
		template.save(person);

		assertThat(template.findById(id, Person.class).getFirstName()).isNull();
	}

	@Test
	public void save_shouldUpdateExistingDocument() {
		VersionedClass one = new VersionedClass(id, "foo", 0);
		template.save(one);

		template.save(new VersionedClass(id, "foo1", one.version));

		VersionedClass value = template.findById(id, VersionedClass.class);
		assertThat(value.version).isEqualTo(2);
		assertThat(value.field).isEqualTo("foo1");
	}

	@Test
	public void save_shouldSetVersionWhenSavingTheSameDocument() {
		VersionedClass one = new VersionedClass(id, "foo");
		template.save(one);
		template.save(one);
		template.save(one);

		assertThat(one.version).isEqualTo(3);
	}

	@Test
	public void save_shouldUpdateAlreadyExistingDocument() throws Exception {
		AtomicLong counter = new AtomicLong();
		int numberOfConcurrentSaves = 5;

		VersionedClass initial = new VersionedClass(id, "value-0");
		template.save(initial);
		assertThat(initial.version).isEqualTo(1);

		AsyncUtils.executeConcurrently(numberOfConcurrentSaves, () -> {
            boolean saved = false;
            while(!saved) {
                long counterValue = counter.incrementAndGet();
                VersionedClass messageData = template.findById(id, VersionedClass.class);
                messageData.field = "value-" + counterValue;
                try {
                    template.save(messageData);
                    saved = true;
                } catch (OptimisticLockingFailureException e) {
                }
            }
        });

		VersionedClass actual = template.findById(id, VersionedClass.class);

		assertThat(actual.field).isNotEqualTo(initial.field);
		assertThat(actual.version).isNotEqualTo(initial.version);
		assertThat(actual.version).isEqualTo(initial.version + numberOfConcurrentSaves);
	}

	@Test
	public void save_shouldSaveOnlyFirstDocumentAndNextAttemptsShouldFailWithOptimisticLockingException() throws Exception {
		AtomicLong counter = new AtomicLong();
		AtomicLong optimisticLockCounter = new AtomicLong();
		int numberOfConcurrentSaves = 5;

		AsyncUtils.executeConcurrently(numberOfConcurrentSaves, () -> {
			long counterValue = counter.incrementAndGet();
			String data = "value-" + counterValue;
			VersionedClass messageData = new VersionedClass(id, data);
            try {
                template.save(messageData);
            } catch (OptimisticLockingFailureException e) {
                optimisticLockCounter.incrementAndGet();
            }
        });

		assertThat(optimisticLockCounter.intValue()).isEqualTo(numberOfConcurrentSaves - 1);
	}

	@Test
	public void findById_shouldSetVersionEqualToNumberOfModifications() throws Exception {
		template.insert(new VersionedClass(id, "foobar"));
		template.update(new VersionedClass(id, "foobar1"));
		template.update(new VersionedClass(id, "foobar2"));

		Record raw = client.get(new Policy(), new Key(getNameSpace(), "versioned-set", id));
		assertThat(raw.generation).isEqualTo(3);
		VersionedClass actual = template.findById(id, VersionedClass.class);
		assertThat(actual.version).isEqualTo(3);
	}

	@Test
	public void findById_shouldReadVersionedClassWithAllArgsConstructor() {
		VersionedClassWithAllArgsConstructor inserted = new VersionedClassWithAllArgsConstructor(id, "foobar", 0L);
		template.insert(inserted);

		assertThat(template.findById(id, VersionedClassWithAllArgsConstructor.class).version).isEqualTo(1L);

		template.update(new VersionedClassWithAllArgsConstructor(id, "foobar1", inserted.version));

		assertThat(template.findById(id, VersionedClassWithAllArgsConstructor.class).version).isEqualTo(2L);
	}

	@Test
	public void save_shouldSaveMultipleTimeDocumentWithoutVersion() {
		CustomCollectionClass one = new CustomCollectionClass(id, "numbers");

		template.save(one);
		template.save(one);

		assertThat(template.findById(id, CustomCollectionClass.class)).isEqualTo(one);
	}

	@Test
	public void save_shouldUpdateDocumentDataWithoutVersion() {
		CustomCollectionClass first = new CustomCollectionClass(id, "numbers");
		CustomCollectionClass second = new CustomCollectionClass(id, "hot dog");

		template.save(first);
		template.save(second);

		assertThat(template.findById(id, CustomCollectionClass.class)).isEqualTo(second);
	}

	@Test
	public void findById_shouldReturnNullForNonExistingKey() throws Exception {
		Person one = template.findById("person-non-existing-key", Person.class);

		assertThat(one).isNull();
	}

	@Test
	public void findById_shouldReturnNullForNonExistingKeyIfTouchOnReadSetToTrue() throws Exception {
		DocumentWithTouchOnRead one = template.findById("non-existing-key", DocumentWithTouchOnRead.class);

		assertThat(one).isNull();
	}

	@Test
	public void findById_shouldIncreaseVersionIfTouchOnReadSetToTrue() throws Exception {
		DocumentWithTouchOnRead doc = new DocumentWithTouchOnRead(String.valueOf(id));
		template.save(doc);

		DocumentWithTouchOnRead actual = template.findById(doc.getId(), DocumentWithTouchOnRead.class);

		assertThat(actual.getVersion()).isEqualTo(doc.getVersion() + 1);
	}



	@Test
	public void shouldInsertAndFindWithCustomCollectionSet() throws Exception {
		CustomCollectionClass initial = new CustomCollectionClass(id, "data0");
		template.insert(initial);

		Record record = client.get(new Policy(), new Key(getNameSpace(), "custom-set", id));

		assertThat(record.getString("data")).isEqualTo("data0");
		assertThat(template.findById(id, CustomCollectionClass.class)).isEqualTo(initial);
	}

	@Test
	public void insertsSimpleEntityCorrectly() {
		Person person = new Person(id,"Oliver");
		person.setAge(25);
		template.insert(person);

		Person person1 =  template.findById(id, Person.class);
		assertThat(person1).isEqualTo(person);
	}

	@Test
	public void findbyIdFail() {
		Person person = new Person(id,"Oliver");
		person.setAge(25);
		template.insert(person);

		Person person1 =  template.findById("Person", Person.class);
		assertThat(person1).isNull();
	}

	@Test
	public void throwsExceptionForDuplicateIds() {
		Person person = new Person(id,"Amol");
		person.setAge(28);

		template.insert(person);
		assertThatThrownBy(() -> template.insert(person))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	public void rejectsDuplicateIdInInsertAll() {
		Person person = new Person(id, "Amol");
		person.setAge(28);

		List<Person> records = new ArrayList<Person>();
		records.add(person);
		records.add(person);

		assertThatThrownBy(() -> template.insertAll(records))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	public void shouldThrowExceptionOnUpdateForNonexistingKey() {
		assertThatThrownBy(() -> template.update(new Person(id, "svenfirstName", 11)))
				.isInstanceOf(DataRetrievalFailureException.class);
	}

	@Test
	public void testUpdateSuccess(){
		Person person = new Person(id,"WLastName",11);
		template.insert(person);

		template.update(person);

		Person result = template.findById(id, Person.class);

		assertThat(result.getAge()).isEqualTo(11);
	}

	@Test
	public void testSimpleDeleteByObject(){
		Person personSven02 = new Person(id,"QLastName",21);

		template.insert(personSven02);

		boolean deleted = template.delete(personSven02);
		assertThat(deleted).isTrue();

		Person result = template.findById(id, Person.class);
		assertThat(result).isNull();
	}

	@Test
	public void testSimpleDeleteById(){
		Person personSven02 = new Person(id,"QLastName",21);

		template.insert(personSven02);

		boolean deleted = template.delete(id, Person.class);
		assertThat(deleted).isTrue();

		Person result = template.findById(id, Person.class);
		assertThat(result).isNull();
	}

	@Test
	public void StoreAndRetrieveMap(){
		Person personSven02 = new Person(id,"QLastName",50);
		Map<String, String> map = new HashMap<String, String>();
		map.put("key", "value");
			personSven02.setMap(map);

		template.insert(personSven02);

		Person findDate = template.findById(id, Person.class);

		assertThat(findDate.getMap()).isEqualTo(map);
	}

	@Test
	public void StoreAndRetrieveList(){
		Person personSven02 = new Person(id, "QLastName", 50);
		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "value1");
		map.put("key2", "value2");
		map.put("key3", "value3");
		personSven02.setMap(map);
		ArrayList<String> list = new ArrayList<String>();
		list.add("string1");
		list.add("string2");
		list.add("string3");
		personSven02.setList(list);

		template.insert(personSven02);

		Person findDate = template.findById(id, Person.class);

		assertThat(findDate.getMap()).isEqualTo(map);
		assertThat(findDate.getList()).isEqualTo(list);
	}

	@Test
	public void TestAddToList() {
		Person personSven02 = new Person(id, "QLastName", 50);
		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "value1");
		map.put("key2", "value2");
		map.put("key3", "value3");
		personSven02.setMap(map);
		ArrayList<String> list = new ArrayList<String>();
		list.add("string1");
		list.add("string2");
		list.add("string3");
		personSven02.setList(list);

		template.insert(personSven02);

		Person personWithList = template.findById(id, Person.class);
		personWithList.getList().add("Added something new");
		template.update(personWithList);
		Person personWithList2 = template.findById(id, Person.class);

		assertThat(personWithList2).isEqualTo(personWithList);
		assertThat(personWithList2.getList()).hasSize(4);
	}

	@Test
	public void TestAddToMap() {

		Person personSven02 = new Person(id, "QLastName", 50);
		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "value1");
		map.put("key2", "value2");
		map.put("key3", "value3");
		personSven02.setMap(map);
		ArrayList<String> list = new ArrayList<String>();
		list.add("string1");
		list.add("string2");
		list.add("string3");
		personSven02.setList(list);

		template.insert(personSven02);

		Person personWithList = template.findById(id, Person.class);
		personWithList.getMap().put("key4","Added something new");
		template.update(personWithList);
		Person personWithList2 = template.findById(id, Person.class);

		assertThat(personWithList2).isEqualTo(personWithList);
		assertThat(personWithList2.getMap()).hasSize(4);
		assertThat(personWithList2.getMap().get("key4")).isEqualTo("Added something new");

	}

	@Test
	public void deleteByTypeShouldDeleteAllDocumentsWithCustomSetName() throws Exception {
		String id1 = nextId();
		String id2 = nextId();
		template.save(new CustomCollectionClass(id1, "field-value"));
		template.save(new CustomCollectionClass(id2, "field-value"));

		assertThat(template.findByIds(Arrays.asList(id1, id2), CustomCollectionClass.class)).hasSize(2);

		template.delete(CustomCollectionClass.class);

		// truncate is async operation that is why we need to wait until
		// it completes
		await().atMost(TEN_SECONDS)
				.untilAsserted(() -> {
					assertThat(template.findById(id1, CustomCollectionClass.class)).isNull();
					assertThat(template.findById(id2, CustomCollectionClass.class)).isNull();
				});
	}

	@Test
	public void deleteByTypeShouldDeleteAllDocumentsWithDefaultSetName() throws Exception {
		String id1 = nextId();
		String id2 = nextId();
		template.save(new DocumentWithExpiration(id1));
		template.save(new DocumentWithExpiration(id2));

		template.delete(DocumentWithExpiration.class);

		// truncate is async operation that is why we need to wait until
		// it completes
		await().atMost(TEN_SECONDS)
				.untilAsserted(() -> {
					assertThat(template.findById(id1, DocumentWithExpiration.class)).isNull();
					assertThat(template.findById(id2, DocumentWithExpiration.class)).isNull();
				});
	}

	@Test
	public void deleteByMullTypeThrowsException() {
		assertThatThrownBy(() -> template.delete(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Type must not be null!");
	}

	@Test
	public void countFindsAllItemsByGivenCriteria() {
		template.insert(new Person(id, "vasili", 50));
		template.insert(new Person(nextId(), "vasili", 51));
		template.insert(new Person(nextId(), "vasili", 52));
		template.insert(new Person(nextId(), "petya", 52));

		long vasyaCount = template.count(new Query(new Criteria().is("vasili", "firstName")), Person.class);

		assertThat(vasyaCount).isEqualTo(3);

		long vasya51Count = template.count(new Query(new Criteria().is("vasili", "firstName").and("age").is(51, "age")), Person.class);

		assertThat(vasya51Count).isEqualTo(1);

		long petyaCount = template.count(new Query(new Criteria().is("petya", "firstName")), Person.class);

		assertThat(petyaCount).isEqualTo(1);
	}

	@Test
	public void countFindsAllItemsByGivenCriteriaAndRespectsIgnoreCase() {
		template.insert(new Person(id, "VaSili", 50));
		template.insert(new Person(nextId(), "vasILI", 51));
		template.insert(new Person(nextId(), "vasili", 52));

		Query query1 = new Query(new Criteria().startingWith("vas", "firstName", Part.IgnoreCaseType.ALWAYS));
		assertThat(template.count(query1, Person.class)).isEqualTo(3);

		Query query2 = new Query(new Criteria().startingWith("VaS", "firstName", Part.IgnoreCaseType.NEVER));
		assertThat(template.count(query2, Person.class)).isEqualTo(1);
	}

	@Test
	public void countReturnsZeroIfNoDocumentsByProvidedCriteriaIsFound() {
		long count = template.count(new Query(new Criteria().is("nastyushka", "firstName")), Person.class);

		assertThat(count).isZero();
	}

	@Test(expected = IllegalArgumentException.class)
	public void countRejectsNullEntityClass() {
		template.count(null, (Class<?>) null);
	}

	@Test
	public void save_rejectsNullObjectToBeSaved() {
		assertThatThrownBy(() -> template.save(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void shouldAdd() {
		Person one = Person.builder().id(id).age(25).build();
		template.insert(one);

		Person updated = template.add(one, "age", 1);

		assertThat(updated.getAge()).isEqualTo(26);
	}

	@Test
	public void shouldAppend() throws Exception {
		Person one = Person.builder().id(id).firstName("Nas").build();
		template.insert(one);

		Person appended = template.append(one, "firstName", "tya");

		assertThat(appended).isEqualTo(Person.builder().id(id).firstName("Nastya").build());
		assertThat(appended.getFirstName()).isEqualTo("Nastya");
		assertThat(template.findById(id, Person.class).getFirstName()).isEqualTo("Nastya");
	}

	@Test
	public void shouldAppendMultipleFields() throws Exception {
		Person one = Person.builder().id(id).firstName("Nas").emailAddress("nastya@").build();
		template.insert(one);

		Map<String, String> toBeUpdated = new HashMap<>();
		toBeUpdated.put("firstName", "tya");
		toBeUpdated.put("email", "gmail.com");
		Person appended = template.append(one, toBeUpdated);

		assertThat(appended.getFirstName()).isEqualTo("Nastya");
		assertThat(appended.getEmailAddress()).isEqualTo("nastya@gmail.com");
		Person actual = template.findById(id, Person.class);
		assertThat(actual.getFirstName()).isEqualTo("Nastya");
		assertThat(actual.getEmailAddress()).isEqualTo("nastya@gmail.com");
	}

	@Test
	public void shouldPrepend() throws Exception {
		Person one = Person.builder().id(id).firstName("tya").build();
		template.insert(one);

		Person appended = template.prepend(one, "firstName", "Nas");

		assertThat(appended.getFirstName()).isEqualTo("Nastya");
		assertThat(template.findById(id, Person.class).getFirstName()).isEqualTo("Nastya");
	}

	@Test
	public void shouldPrependMultipleFields() throws Exception {
		Person one = Person.builder().id(id).firstName("tya").emailAddress("gmail.com").build();
		template.insert(one);

		Map<String, String> toBeUpdated = new HashMap<>();
		toBeUpdated.put("firstName", "Nas");
		toBeUpdated.put("email", "nastya@");
		Person appended = template.prepend(one, toBeUpdated);

		assertThat(appended.getFirstName()).isEqualTo("Nastya");
		assertThat(appended.getEmailAddress()).isEqualTo("nastya@gmail.com");
		Person actual = template.findById(id, Person.class);
		assertThat(actual.getFirstName()).isEqualTo("Nastya");
		assertThat(actual.getEmailAddress()).isEqualTo("nastya@gmail.com");

	}

	@Test
	public void shouldPersistWithCustomWritePolicy() throws Exception {
		CustomCollectionClass initial = new CustomCollectionClass(id, "data");

		WritePolicy writePolicy = new WritePolicy();
		writePolicy.recordExistsAction = RecordExistsAction.CREATE_ONLY;

		template.persist(initial, writePolicy);

		CustomCollectionClass actual = template.findById(id, CustomCollectionClass.class);
		assertThat(actual).isEqualTo(initial);
	}

	@Test(expected = DataRetrievalFailureException.class)
	public void shouldNotPersistWithCustomWritePolicy() throws Exception {
		CustomCollectionClass initial = new CustomCollectionClass(id, "data");

		WritePolicy writePolicy = new WritePolicy();
		writePolicy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;

		template.persist(initial, writePolicy);
	}

	@Test(expected = DuplicateKeyException.class)
	public void shouldTranslateException() {
		Key key = new Key(template.getNamespace(), "shouldTranslateException", "shouldTranslateException");
		Bin bin = new Bin("bin_name", "bin_value");

		template.getAerospikeClient().add(null, key, bin);
		template.execute(() -> {
			AerospikeClient client = template.getAerospikeClient();
			WritePolicy writePolicy = new WritePolicy(client.getWritePolicyDefault());
			writePolicy.recordExistsAction = RecordExistsAction.CREATE_ONLY;

			client.add(writePolicy, key, bin);
			return true;
		});
	}

	@Test
	public void exists_shouldReturnTrueIfValueIsPresent() {
		Person one = Person.builder().id(id).firstName("tya").emailAddress("gmail.com").build();
		template.insert(one);

		assertThat(template.exists(id, Person.class)).isTrue();
	}

	@Test
	public void exists_shouldReturnFalseIfValueIsAbsent() {
		assertThat(template.exists(id, Person.class)).isFalse();
	}

	@Test
	public void deleteById_shouldReturnFalseIfValueIsAbsent() {
		assertThat(template.delete(id, Person.class)).isFalse();
	}

	@Test
	public void deleteByObject_shouldReturnFalseIfValueIsAbsent() {
		Person one = Person.builder().id(id).firstName("tya").emailAddress("gmail.com").build();
		assertThat(template.delete(one)).isFalse();
	}

	@Test
	public void findByIds_shouldFindExisting() {
		Person firstPerson = Person.builder().id(nextId()).firstName("first").emailAddress("gmail.com").build();
		Person secondPerson = Person.builder().id(nextId()).firstName("second").emailAddress("gmail.com").build();
		template.save(firstPerson);
		template.save(secondPerson);

		List<String> ids = Arrays.asList(nextId(), firstPerson.getId(), secondPerson.getId());

		List<Person> actual = template.findByIds(ids, Person.class);

		assertThat(actual).containsExactly(firstPerson, secondPerson);
	}


	@Test
	public void findByIds_shouldReturnEmptyList() {
		List<Person> actual = template.findByIds(Collections.emptyList(), Person.class);
		assertThat(actual).isEmpty();
	}

	@Test(expected = IllegalStateException.class)
	public void findById_shouldFailOnTouchOnReadWithExpirationProperty() {
		String id = nextId();
		template.insert(new DocumentWithTouchOnReadAndExpirationProperty(id, EXPIRATION_ONE_MINUTE));
		template.findById(id, DocumentWithTouchOnReadAndExpirationProperty.class);
	}

	@Test
	public void findInRange_shouldFindLimitedNumberOfDocuments() throws Exception {
		IntStream.range(20, 27)
				.forEach(age -> template.insert(Person.builder().id(nextId()).age(age).build()));

		int skip = 0;
		int limit = 5;
		Stream<Person> stream = template.findInRange(skip, limit, Sort.unsorted(), Person.class);

		assertThat(stream)
				.hasSize(5)
				.extracting("age").containsAnyOf(20, 21, 22, 23, 24, 25, 26);
	}

	@Test
	public void findInRange_shouldFindLimitedNumberOfDocumentsAndSkip() throws Exception {
		IntStream.range(20, 27)
				.forEach(age -> template.insert(Person.builder().id(nextId()).age(age).build()));

		int skip = 3;
		int limit = 5;
		Stream<Person> stream = template.findInRange(skip, limit, Sort.unsorted(), Person.class);

		assertThat(stream)
				.hasSize(4)
				.extracting("age").containsAnyOf(20, 21, 22, 23, 24, 25, 26);
	}

	@Test
	public void findAll_findsAllExistingDocuments() {
		List<Person> persons = IntStream.range(1, 10)
				.mapToObj(age -> Person.builder().id(nextId()).firstName("Nastya").age(age).build())
				.collect(Collectors.toList());
		template.insertAll(persons);

		Stream<Person> result = template.findAll(Person.class);

		assertThat(result).containsOnlyElementsOf(persons);
	}

	@Test
	public void findAll_findsNothing() throws Exception {
		Stream<Person> result = template.findAll(Person.class);

		assertThat(result).isEmpty();
	}

	@Test
	public void save_shouldConcurrentlyUpdateDocumentIfTouchOnReadIsTrue() throws Exception {
		int numberOfConcurrentUpdate = 10;
		AsyncUtils.executeConcurrently(numberOfConcurrentUpdate, new Runnable() {
			@Override
			public void run() {
				try {
					DocumentWithTouchOnRead existing = template.findById(id, DocumentWithTouchOnRead.class) ;
					DocumentWithTouchOnRead toUpdate;
					if (existing != null) {
						toUpdate = new DocumentWithTouchOnRead(id, existing.getField() + 1, existing.getVersion());
					} else {
						toUpdate = new DocumentWithTouchOnRead(id, 1);
					}

					template.save(toUpdate);
				} catch (ConcurrencyFailureException e) {
					//try again
					run();
				}
			}
		});

		DocumentWithTouchOnRead actual = template.findById(id, DocumentWithTouchOnRead.class);
		assertThat(actual.getField()).isEqualTo(numberOfConcurrentUpdate);
	}

	@Test
	public void find_throwsExceptionForUnsortedQueryWithSpecifiedOffsetValue() {
		Query query = new Query((Sort) null);
		query.setOffset(1);

		assertThatThrownBy(() -> template.find(query, Person.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Unsorted query must not have offset value. For retrieving paged results use sorted query.");
	}

	@Test
	public void save_shouldSaveAndFindDocumentWithByteArrayField() {
		DocumentWithByteArray document = new DocumentWithByteArray(id, new byte[]{1, 0, 0, 1, 1, 1, 0, 0});

		template.save(document);

		DocumentWithByteArray result = template.findById(id, DocumentWithByteArray.class);

		assertThat(result).isEqualTo(document);
	}
}