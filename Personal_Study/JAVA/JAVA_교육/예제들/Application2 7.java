package com.greedy.section03.api.math;

public class Application2 {
	public static void main(String[] args) {
		
		/* 난수의 활용*/
		/* 
		 * Math.random()을 이용해 발생하는 난수는 0부터 1직전(0.999...) 까지의 실수 범위를
		 * 반환한다.
		 * 필요에 따라 정수 형태의 값을 원하는 범위만큼 발생 시켜야 하는 경우들이 존재하는데
		 * 필요한 범위까지의 난수를 예제를 통해 발생시켜 보자.
		 */
		
		/*
		 * 원하는 범위의 난수를 Math를 활용해서 구하는 공식
		 * (int)(Math.random() * 구하려는 난수의 갯수) + 구하려는 난수의 최소값(시작값)
		 */
		
		/* 0 ~ 9까지의 난수 발생 */
		int random1 = (int)(Math.random() * 10) +0;
		System.out.println("0부터 9 사이의 난수: " + random1);
		
		/* 1 ~ 10까지의 난수 발생 */
		int random2 = (int)(Math.random() * 10) + 1;
		System.out.println("1부터 10 사이의 난수 : " + random2);
		
		/* 10 ~ 15까지의 난수 발생 */
		int random3 = (int)(Math.random() * 6) + 10;
		System.out.println("10부터 15사이의 난수 : " + random3);
		
		/* -1 ~ 3까지의 난수 발생 */
		int random4 = (int)(Math.random() * 5) + (-1);
		System.out.println("-1부터 3사이의 난수 : " + random4);
		
		/* -128 ~ 127까지의 난수 발생 */
		int random5 = (int)(Math.random() * 256) + (-128);
		System.out.println("-128부터 127사이의 난수 : " +random5);
	}

}
