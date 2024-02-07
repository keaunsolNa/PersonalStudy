package com.greedy.section02.set.run;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public class LottoTest {
	public static void main(String[] args) {

		/* 로또 번호 생성기 만들기 */
		/* 1. 배열로 로또 번호 6개 생성하기 (중복제거, 오름차순) */
		int[] lotto = new int[6];
		
		/* 중복값 제거하며 로또 번호 생성 */
		for(int index =0; index < lotto.length; index++) {
			lotto[index] = (int)(Math.random() *45) +1;
			
			/* 중복 제거를 위해 기존의 위치에 생성된 랜덤수(j번째 랜덤수)와 비교하는 구문 */
			for(int j = 0; j < index; j++) {
				if(lotto[j] == lotto[index]) {
					index--;							// 다시 방금의 index 위치에서 랜덤수를 생성하기 위해 index를 하나 줄인다.
				}
			}
		}
		
		/* 생성된 로또 번호 순차정렬을 이용한 오름차순 */
		int temp = 0;
		for(int i = 0; i <lotto.length; i++) {
			for(int j=0; j < i; j++) {
				if(lotto[j] > lotto[i]) {
					temp = lotto[i];
					lotto[i] = lotto[j];
					lotto[j] = temp;
				}
			}
		}
		
		System.out.println(Arrays.toString(lotto));
		
		/* 2. TreeSet을 활용해서 로또 번호 생성하기 */
		Set<Integer> s = new TreeSet<>();
		
		while(true) {
			s.add((int)(Math.random() * 45) +1);
			if(s.size() > 5) break;
		}
		
		/* 오름차순 */
		System.out.println(s);
		
		/* 내림차순 */
		System.out.println(((TreeSet<Integer>)s).descendingSet());
	}
}
