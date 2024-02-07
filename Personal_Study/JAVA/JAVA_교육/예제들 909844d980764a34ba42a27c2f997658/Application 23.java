package com.greedy.section03.sync;

public class Application {
	public static void main(String[] args) {

		/* 공유 인스턴스 생성 */
		Buffer buffer = new Buffer();
		
		/* 동일한 버퍼 인스턴스를 공유하는 생산자와 소비자 스레드 생성 */
		Thread t1 = new Producer(buffer);
		Thread t2 = new Consumer(buffer);
		
		/* 생산자와 소비자 스레드 실행 */
		t1.start();
		t2.start();
		
		/*
		 * 그냥 실행하면 IllegalMonitorStateException이 발생한다.
		 * 모니터(Monitor) :멀티스레드가 객체에 접근 시 동기화를 지원하기 위해 각 객체마다 존재하는 것
		 */
		
		
		
		
		
	}
}
