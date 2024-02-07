package com.greedy.section04.comparison_operator;

public class Allpication1 {
	public static void main(String[] args) {
		
		/* 비교 연산자 */
		/* 비교 연산자는 피연산자 사이에서 상대적인 크기를 판단하여 참 혹은 거짓을 반환하는 연산자이다. */
		
		/* 1. 숫자값 비교 */
		/* 1-1. 정수 비교 */
		int iNum1 = 10;
		int iNum2 = 20;
		
		System.out.println("======== 정수값 비교 ========");
		System.out.println("iNum1과 iNum2가 같은지 비교: " + (iNum1 == iNum2));
		System.out.println("iNum1과 inum2가 같지 않은지 비교: " + (iNum1 != iNum2));
		System.out.println("iNum1이 inum2보다 큰지 비교: " + (iNum1 > iNum2));
		System.out.println("iNum1이 inum2보다 크거나 같은지 비교: " + (iNum1 >= iNum2));
		System.out.println("iNum1이 inum2보다 작은지 비교: " + (iNum1 < iNum2));
		System.out.println("iNum1이 inum2보다 작거나 같은지 비교: " + (iNum1 <= iNum2));
		
		/* 1-1. 실수 비교 */
		double dNum1 = 10.0;
		double dNum2 = 20.0;
		
		System.out.println("======== 실수값 비교 ========");
		System.out.println("dNum1과 dNum2가 같은지 비교: " + (dNum1 == dNum2));
		System.out.println("dNum1과 dnum2가 같지 않은지 비교: " + (dNum1 != dNum2));
		System.out.println("dNum1이 dnum2보다 큰지 비교: " + (dNum1 > dNum2));
		System.out.println("dNum1이 dnum2보다 크거나 같은지 비교: " + (dNum1 >= dNum2));
		System.out.println("dNum1이 dnum2보다 작은지 비교: " + (dNum1 < dNum2));
		System.out.println("dNum1이 dnum2보다 작거나 같은지 비교: " + (dNum1 <= dNum2));
		                            
		/* 1-3. 문자값 비교*/
		char ch1 = 'a';
		char ch2 = 'A';
		
		System.out.println("ch1은 int로: " + (int)ch1);		// 97
		System.out.println("ch2은 int로: " + (int)ch2);		// 65
		
		System.out.println("======== 문자값 비교 ========");
		System.out.println("ch1과 ch2가 같은지 비교: " + (ch1 == ch2));
		System.out.println("ch1과 ch2가 같지 않은지 비교: " + (ch1 != ch2));
		System.out.println("ch1이 ch2보다 큰지 비교: " + (ch1 > ch2));
		System.out.println("ch1이 ch2보다 크거나 같은지 비교: " + (ch1 >= ch2));
		System.out.println("ch1이 ch2보다 작은지 비교: " + (ch1 < ch2));
		System.out.println("ch1이 ch2보다 작거나 같은지 비교: " + (ch1 <= ch2));
		
		/* 1-4. 논리값 비교 */
		/* 논리 값은 ==과 !=을 제외하고 대소 비교는 불가능하다. */
		boolean bool1 = true;
		boolean bool2 = false;
		
		System.out.println("======== 문자값 비교 ========");
		System.out.println("boolean1과 boolean2가 같은지 비교: " + (bool1 == bool2));
		System.out.println("boolean1과 boolean2가 같지 않은지 비교: " + (bool1 != bool2));
//		System.out.println("boolean1이 boolean2보다 큰지 비교: " + (bool1 > bool2));
//		System.out.println("boolean1이 boolean2보다 크거나 같은지 비교: " + (bool1 >= bool2));
//		System.out.println("boolean1이 boolean2보다 작은지 비교: " + (bool1 < bool2));
//		System.out.println("boolean1이 boolean2보다 작거나 같은지 비교: " + (bool1 <= bool2));		                    
	}
}
