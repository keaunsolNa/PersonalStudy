package com.greedy.section04.practice1;

public class Star1 implements Runnable{

	@Override
	public void run() {
		try {
			for(int i =0; i< 20; i++) {

				for(int k = 0; k < 30; k++){
					System.out.println();
				}
				/* 하트 출력 */
				for(int j =0; j< i; j++) {
					System.out.print(" ");
				}
				System.out.println("♡");
				/* 스타 출력 */
				for(int j =0; j< i; j++) {
					System.out.print(" ");
				} 
				System.out.println("☆");
				
				Thread.sleep(1000);
			}
			
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
			
	}

}
