package com.greedy.section03.map.run;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Appliacation1 {
	public static void main(String[] args) {

		/* HashMap */
		HashMap hMap = new HashMap();
		Map hMap2 = new HashMap();
		
		/* 키와 값 객체(Value) 저장하기 */
		hMap2.put("one", new java.util.Date());
		hMap2.put("one", "red apple");		// 기존에 존재하는 키와 일치하면 나중에 put한 key와 value가 덮어쓴다.
		hMap2.put(33, 123.5);
		
		System.out.println("hMap2: " + hMap2);
		
		hMap2.put(11, "yellow banana");
		hMap2.put(9, "yellow banana");		
		
		System.out.println("hMap2: " + hMap2);
		
		/* 값 객체 불러오기 */
		System.out.println("11이라는 key값에 대한 value객체: " + hMap2.get(11));
		System.out.println("\"one\"이라는 key값에 대한 value객체: " + hMap2.get("one"));

		/* 키 객체를 가지고 해당하는 키와 값 객체를 삭제 처리할 수 있다. */
		hMap2.remove("one");
		System.out.println("hMap2: " + hMap2);
		
		/* 저장된 엔트리(키와 값의 쌍)의 수를 확인할 때 */
		System.out.println("hMap2에 저장된 entry의 수: " + hMap2.size());
		
		Map<String, String> hMap3 = new HashMap<>();

		hMap3.put("one", "java 11");
		hMap3.put("two", "oracle 18c");
		hMap3.put("three", "jdbc");
		hMap3.put("four", "HTML5");
		hMap3.put("five", "CSS3");

		/*
		 * 1. keySet()을 이용해서 키만 따로 Set으로 만들고,
		 * 	  Set게열이 쓸 수 있는 iterator()로 키에 대한 목록을 만든다.
		 * 	  (Key를 알면 get(key)메소드로 value를 알 수 있다.)
		 */
		Set<String> Keys = hMap3.keySet();
		Iterator<String> keyIter = Keys.iterator();
		
		while(keyIter.hasNext()) {
			String key = keyIter.next();
			String value = hMap3.get(key);
			System.out.println(key + " = " + value);
		}
		
		/*
		 * 2. 저장된 value객체들만 (key객체는 해당 안됨) values()로 Collection으로 만들 수 있다.
		 * 	  Collection은 iterator()의 toArray() 메소드를 제공해 주므로 두가지 방식으로 
		 *    처리할 수 있다.
		 */
		Collection<String> values = hMap3.values();
		
		/* 2-1. Iterator()로 목록 만들어서 처리 */
		Iterator<String> valueIter = values.iterator();
		while(valueIter.hasNext()) {
			System.out.println(valueIter.next());
		}
		
		/* 2-2. 배열로 만들어서 처리 */
		Object[] valueArr = values.toArray();
		for(int i = 0; i < valueArr.length; i++) {
			System.out.println(i + " : " + valueArr[i]);
		}
		
		/*
		 * 3. Map의 entrySet()을 이용해서 키와 값을 동시에 처리
		 * 	  (키가 없이 값만 뽑을 수도 있고 키만 뽑을 수도 있다.)
		 *    java.util.May.Entry : 키 객체와 값 객체를 쌍으로 묶은 자료형
		 */
		Set<Map.Entry<String, String>> set = hMap3.entrySet();
		Iterator<Map.Entry<String, String>> entryIter = set.iterator();
		
		while(entryIter.hasNext()) {
			Map.Entry<String, String> entry = entryIter.next();
			System.out.println(entry.getKey() + " : " + entry.getValue());
		}
		
		
		
	}
}
